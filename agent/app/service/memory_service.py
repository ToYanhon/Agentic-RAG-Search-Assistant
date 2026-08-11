"""用户长期记忆 — 跨 session 的用户事实与偏好。

存储结构:
  - Redis:  user:{id}:memory  List → [JSON {fact, created_at}, ...]（热层，读路径）
  - SQLite: memories 表（落盘主存，与 sessions/messages 同模式）
  双写：每次写操作同步 Redis + SQLite；Redis 空但 SQLite 有数据时惰性恢复。

遗忘机制:
  - 显式遗忘: forget_memory(keyword) 删除包含关键词的记忆
  - 时效遗忘: 每条记忆带 created_at，读取时惰性清理超期条目
  - 容量上限: 每用户最多 MAX_MEMORIES 条，超出淘汰最旧
  - 去重: 字符串相等直接忽略；语义重复由 add_memory_smart（embedding 相似度 + LLM 裁决）
"""

import json
import logging
import time

from app.config import settings
from app.core.redis_pool import get_redis
from app.prompt.prompts import DEDUP_PROMPT, EXTRACT_PROMPT

logger = logging.getLogger(__name__)

MAX_MEMORIES = 20
MEMORY_TTL = 90 * 86400  # 90 天

# 语义去重：与现有记忆的最高余弦相似度 ≥ 此阈值视为重复候选，交由 LLM 裁决
DEDUP_SIM_THRESHOLD = 0.8

# 每个 session 提取节流（秒）：同一会话内避免每轮都调 LLM
_EXTRACT_COOLDOWN: dict[str, float] = {}


def _r():
    return get_redis()


def _key(user_id: int) -> str:
    return f"user:{user_id}:memory"


async def get_memory(user_id: int) -> list[str]:
    """返回活跃记忆（惰性清理超期条目；Redis 空时从 SQLite 恢复）。"""
    entries = await get_memory_entries(user_id)
    return [e["fact"] for e in entries]


async def get_memory_entries(user_id: int) -> list[dict]:
    """返回活跃记忆条目 [{fact, created_at}, ...]（含惰性清理与 SQLite 恢复）。"""
    r = _r()
    key = _key(user_id)
    raw = await r.lrange(key, 0, -1)

    now = time.time()
    active: list[dict] = []
    expired: list[dict] = []
    for item in raw:
        try:
            data = json.loads(item)
        except json.JSONDecodeError:
            expired.append({})
            continue
        if now - data.get("created_at", 0) > MEMORY_TTL:
            expired.append(data)
        else:
            active.append(data)

    if not raw:
        restored = await _restore_from_db_entries(user_id)
        if restored:
            return restored

    if expired:
        await r.delete(key)
        for data in active:
            await r.rpush(key, json.dumps(data, ensure_ascii=False))

    return active


async def _restore_from_db_entries(user_id: int) -> list[dict]:
    """Redis 为空时从 SQLite 恢复完整条目（惰性迁移路径）。"""
    try:
        from app.core import db

        rows = await db.run(
            "SELECT fact, created_at FROM memories WHERE user_id = ? ORDER BY id",
            (user_id,),
        )
        now = time.time()
        active = [
            {"fact": r["fact"], "created_at": r["created_at"]}
            for r in rows
            if now - (r.get("created_at") or 0) <= MEMORY_TTL
        ]
        key = _key(user_id)
        r = _r()
        await r.delete(key)
        for data in active:
            await r.rpush(key, json.dumps(data, ensure_ascii=False))
        if active:
            await r.expire(key, MEMORY_TTL)
        return active
    except Exception:
        logger.exception("memory restore from sqlite failed")
        return []


async def _restore_from_db(user_id: int) -> list[str]:
    """Redis 为空时从 SQLite 恢复（惰性迁移路径）。"""
    entries = await _restore_from_db_entries(user_id)
    return [e["fact"] for e in entries]


async def add_memory(user_id: int, fact: str) -> None:
    """精确去重后写入（字符串完全相等忽略）。"""
    r = _r()
    key = _key(user_id)

    raw = await r.lrange(key, 0, -1)
    for item in raw:
        try:
            if json.loads(item).get("fact") == fact:
                return
        except json.JSONDecodeError:
            continue

    await _append(user_id, fact)
    await r.expire(key, MEMORY_TTL)


async def add_memory_smart(user_id: int, fact: str) -> str:
    """语义去重入口：字符串相等忽略；相似候选交 LLM 裁决合并/保留。

    返回写入结果描述（供工具回显）。"""
    r = _r()
    key = _key(user_id)

    existing = await get_memory(user_id)
    for old in existing:
        if old == fact:
            return "已存在相同的长期记忆，跳过"

    if len(existing) < 2:
        await _append(user_id, fact)
        await r.expire(key, MEMORY_TTL)
        return "已保存到长期记忆"

    try:
        from app.core.embedding import searcher

        vecs = await searcher.embed_texts([fact] + existing)
        target = vecs[0]
        best = max(
            range(len(existing)),
            key=lambda i: sum(a * b for a, b in zip(target, vecs[i + 1])),
        )
        best_sim = sum(a * b for a, b in zip(target, vecs[best + 1]))
    except Exception:
        logger.exception("memory embedding failed, skip dedup")
        await _append(user_id, fact)
        await r.expire(key, MEMORY_TTL)
        return "已保存到长期记忆"

    if best_sim < DEDUP_SIM_THRESHOLD:
        await _append(user_id, fact)
        await r.expire(key, MEMORY_TTL)
        return "已保存到长期记忆"

    merged = await _llm_merge(existing, fact)
    if merged is None or merged == fact:
        await _append(user_id, fact)
        await r.expire(key, MEMORY_TTL)
        return "已保存到长期记忆"

    await _replace(user_id, existing[best], merged)
    await r.expire(key, MEMORY_TTL)
    return f"已与现有记忆合并更新：{merged}"


async def _llm_merge(existing: list[str], fact: str) -> str | None:
    """调 override LLM 裁决合并结果；失败返回 None（降级直接写入）。"""
    try:
        from app.core.llm import build_llm, current_model, current_provider
        from app.core.llm_override import get_llm_override

        ov = get_llm_override()
        llm = build_llm(
            provider=current_provider(),
            model=current_model(),
            api_key=(ov.api_key if ov and ov.api_key else None) or settings.llm_api_key,
            base_url=(ov.base_url if ov and ov.base_url else None)
            or settings.llm_base_url,
            timeout=30,
            tools=None,
        )
        existing_block = "\n".join(f"- {m}" for m in existing)
        result = await llm.ainvoke(
            DEDUP_PROMPT.format(existing=existing_block, fact=fact)
        )
        text = str(result.content).strip()
        import re

        m = re.search(r"\{.*\}", text, re.S)
        if not m:
            return None
        data = json.loads(m.group(0))
        if not data.get("overlap"):
            return None
        merged = str(data.get("merged", "")).strip()
        return merged or None
    except Exception:
        logger.exception("llm merge failed, fallback to append")
        return None


async def _append(user_id: int, fact: str) -> None:
    """写入 Redis + SQLite（双写）。"""
    r = _r()
    key = _key(user_id)
    entry = json.dumps({"fact": fact, "created_at": int(time.time())}, ensure_ascii=False)
    await r.rpush(key, entry)

    count = await r.llen(key)
    if count > MAX_MEMORIES:
        await r.lpop(key, count - MAX_MEMORIES)

    try:
        from app.core import db

        await db.execute(
            "INSERT OR IGNORE INTO memories (user_id, fact, created_at) VALUES (?, ?, ?)",
            (user_id, fact, int(time.time())),
        )
        # SQLite 同步容量上限：仅保留最新 MAX_MEMORIES 条（Redis 已淘汰最旧）
        await db.execute(
            "DELETE FROM memories WHERE user_id = ? AND id NOT IN ("
            "SELECT id FROM memories WHERE user_id = ? ORDER BY id DESC LIMIT ?)",
            (user_id, user_id, MAX_MEMORIES),
        )
    except Exception:
        logger.exception("memory sqlite write failed")


async def _replace(user_id: int, old_fact: str, new_fact: str) -> None:
    """把旧事实替换为合并后的新事实（双库）。"""
    r = _r()
    key = _key(user_id)
    raw = await r.lrange(key, 0, -1)
    remaining: list[dict] = []
    for item in raw:
        try:
            data = json.loads(item)
            if data.get("fact") == old_fact:
                continue
            remaining.append(data)
        except json.JSONDecodeError:
            continue
    await r.delete(key)
    for data in remaining:
        await r.rpush(key, json.dumps(data, ensure_ascii=False))
    await _append(user_id, new_fact)
    await r.expire(key, MEMORY_TTL)
    try:
        from app.core import db

        await db.execute(
            "DELETE FROM memories WHERE user_id = ? AND fact = ?",
            (user_id, old_fact),
        )
    except Exception:
        logger.exception("memory sqlite replace delete failed")


async def forget_memory(user_id: int, keyword: str) -> int:
    """删除包含关键词的记忆，返回删除条数（双库）。"""
    r = _r()
    key = _key(user_id)
    raw = await r.lrange(key, 0, -1)

    keyword_lower = keyword.lower()
    remaining: list[dict] = []
    removed = 0
    for item in raw:
        try:
            data = json.loads(item)
            if keyword_lower in data.get("fact", "").lower():
                removed += 1
            else:
                remaining.append(data)
        except json.JSONDecodeError:
            removed += 1

    if removed:
        await r.delete(key)
        for data in remaining:
            await r.rpush(key, json.dumps(data, ensure_ascii=False))
        await r.expire(key, MEMORY_TTL)
        try:
            from app.core import db

            await db.execute(
                "DELETE FROM memories WHERE user_id = ? AND lower(fact) LIKE ?",
                (user_id, f"%{keyword_lower}%"),
            )
        except Exception:
            logger.exception("memory sqlite delete failed")

    return removed


async def extract_memory_from_conversation(
    user_id: int, conversation: str, session_id: str | None = None
) -> int:
    """对话结束后台提取记忆：LLM 提炼候选 → 逐条语义去重写入。

    防护：节流（同 session 短期内不重复）；空结果跳过；任何失败仅日志。
    返回新增/更新条数。
    """
    now = time.time()
    if session_id:
        last = _EXTRACT_COOLDOWN.get(session_id, 0)
        if now - last < 5:
            return 0
        _EXTRACT_COOLDOWN[session_id] = now
        if len(_EXTRACT_COOLDOWN) > 5000:
            _EXTRACT_COOLDOWN.clear()
    logger.info("memory extract start", extra={"user_id": user_id, "session_id": session_id})

    try:
        existing = await get_memory(user_id)
    except Exception:
        existing = []

    try:
        from app.core.llm import build_llm, current_model, current_provider
        from app.core.llm_override import get_llm_override

        ov = get_llm_override()
        llm = build_llm(
            provider=current_provider(),
            model=current_model(),
            api_key=(ov.api_key if ov and ov.api_key else None) or settings.llm_api_key,
            base_url=(ov.base_url if ov and ov.base_url else None)
            or settings.llm_base_url,
            timeout=30,
            tools=None,
        )
        existing_block = "\n".join(f"- {m}" for m in existing) or "（无）"
        result = await llm.ainvoke(
            EXTRACT_PROMPT.format(
                existing=existing_block,
                conversation=conversation[:4000],
            )
        )
        text = str(result.content).strip()
        import re

        m = re.search(r"\[.*\]", text, re.S)
        if not m:
            return 0
        facts = json.loads(m.group(0))
        if not isinstance(facts, list):
            return 0
    except Exception:
        logger.exception("memory extract failed")
        return 0

    added = 0
    for f in facts:
        f = str(f).strip()
        if not f:
            continue
        try:
            await add_memory_smart(user_id, f)
            added += 1
        except Exception:
            logger.exception("memory extract add failed")
    logger.info("memory extract done", extra={"user_id": user_id, "facts": len(facts), "added": added})
    return added
