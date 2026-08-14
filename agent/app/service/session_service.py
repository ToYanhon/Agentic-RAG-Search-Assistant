"""对话 session 管理 — SQLite 持久化（主存储），Redis 仅作读缓存。

接口保持向后兼容（chat.py 无感知）。数据永久保留。
"""

import json
import logging
import uuid

from app.core import db
from app.core.redis_pool import get_redis

logger = logging.getLogger(__name__)

# Redis 读缓存：会话列表快照 / 会话最近消息
LIST_CACHE_TTL = 30
MSG_CACHE_TTL = 60
MSG_CACHE_COUNT = 100


def _r():
    return get_redis()


async def _cache_get(key: str) -> str | None:
    try:
        raw = await _r().get(key)
        return raw.decode("utf-8") if isinstance(raw, bytes) else raw
    except Exception:
        return None


async def _cache_del(*keys: str) -> None:
    try:
        await _r().delete(*keys)
    except Exception:
        pass


async def _cache_set(key: str, value: str, ttl: int) -> None:
    try:
        r = _r()
        await r.set(key, value, ex=ttl)
    except Exception:
        pass


async def _list_cache_key(user_id: int) -> str:
    return f"user:{user_id}:sessions:list"


async def create_session(user_id: int, title: str = "新对话") -> str:
    session_id = uuid.uuid4().hex[:12]
    now = int(__import__("time").time())
    await db.execute(
        "INSERT INTO sessions (id, user_id, title, created_at, updated_at) VALUES (?,?,?,?,?)",
        (session_id, user_id, title, now, now),
    )
    await _cache_del(await _list_cache_key(user_id))
    return session_id


async def list_sessions(user_id: int, page: int = 1, page_size: int = 50) -> dict:
    """分页列出用户会话，按最近活动（updated_at）倒序。Redis 快照缓存。"""
    cache_key = await _list_cache_key(user_id)
    cached = await _cache_get(cache_key)
    if cached:
        try:
            data = json.loads(cached)
        except (json.JSONDecodeError, TypeError):
            data = None
        if data is not None:
            return _paginate(data, page, page_size)

    rows = await db.run(
        "SELECT * FROM sessions WHERE user_id=? ORDER BY updated_at DESC",
        (user_id,),
    )
    data = await _fill_sessions(user_id, rows)
    await _cache_set(cache_key, json.dumps(data, ensure_ascii=False), LIST_CACHE_TTL)
    return _paginate(data, page, page_size)


def _paginate(data: list[dict], page: int, page_size: int) -> dict:
    total = len(data)
    page = max(1, page)
    page_size = max(1, min(page_size, 100))
    start = (page - 1) * page_size
    return {
        "items": data[start : start + page_size],
        "total": total,
        "page": page,
        "page_size": page_size,
    }


async def _fill_sessions(user_id: int, rows: list) -> list[dict]:
    """拼装会话列表项目（消息数/预览/累计 usage），两次聚合查询回填。"""
    result: list[dict] = []
    if not rows:
        return result
    sids = [r["id"] for r in rows]
    ph = ",".join("?" * len(sids))

    count_rows = await db.run(
        f"SELECT session_id, COUNT(*) AS c FROM messages "
        f"WHERE session_id IN ({ph}) GROUP BY session_id",
        tuple(sids),
    )
    preview_rows = await db.run(
        f"SELECT m.session_id, m.content FROM messages m "
        f"JOIN (SELECT session_id, MAX(id) AS mid FROM messages "
        f"WHERE session_id IN ({ph}) GROUP BY session_id) t ON m.id = t.mid",
        tuple(sids),
    )
    counts = {r["session_id"]: r["c"] for r in count_rows}
    previews = {r["session_id"]: (r["content"] or "")[:100] for r in preview_rows}

    for r in rows:
        sid = r["id"]
        result.append({
            "id": sid,
            "title": r["title"],
            "created_at": r["created_at"],
            "message_count": counts.get(sid, 0),
            "last_preview": previews.get(sid, ""),
            "usage_in": int(r["usage_in"]),
            "usage_out": int(r["usage_out"]),
            "cost_yuan": float(r["cost_yuan"]),
            "model": r["model"],
        })
    return result


async def add_usage(
    session_id: str,
    usage_in: int,
    usage_out: int,
    cost_yuan: float,
    model: str,
) -> None:
    """累加会话级 token/花费（会话不存在则忽略）。"""
    await db.execute(
        "UPDATE sessions SET usage_in=usage_in+?, usage_out=usage_out+?, "
        "cost_yuan=cost_yuan+?, model=? WHERE id=?",
        (int(usage_in), int(usage_out), float(cost_yuan), model or "", session_id),
    )


async def _last_message_preview(session_id: str) -> str:
    rows = await db.run(
        "SELECT content FROM messages WHERE session_id=? ORDER BY id DESC LIMIT 1",
        (session_id,),
    )
    return (rows[0]["content"] if rows else "")[:100]


async def delete_session(session_id: str, user_id: int) -> bool:
    owner = await get_session_owner(session_id)
    if owner is None or owner != user_id:
        return False
    await db.execute("DELETE FROM messages WHERE session_id=?", (session_id,))
    await db.execute("DELETE FROM sessions WHERE id=?", (session_id,))
    await _cache_del(f"session:{session_id}:messages")
    await _cache_del(await _list_cache_key(user_id))
    return True


async def rename_session(session_id: str, title: str, user_id: int) -> bool:
    owner = await get_session_owner(session_id)
    if owner is None or owner != user_id:
        return False
    await db.execute("UPDATE sessions SET title=? WHERE id=?", (title, session_id))
    await _cache_del(await _list_cache_key(user_id))
    return True


async def get_messages(session_id: str) -> list[dict]:
    """读取会话消息（SQLite 全量），Redis 缓存最近若干条加速聊天首读。"""
    cache_key = f"session:{session_id}:messages"
    cached = await _cache_get(cache_key)
    if cached:
        try:
            cached_list = json.loads(cached)
        except (json.JSONDecodeError, TypeError):
            cached_list = []
        if cached_list:
            return cached_list

    rows = await db.run(
        "SELECT * FROM messages WHERE session_id=? ORDER BY id",
        (session_id,),
    )
    msgs = [_row_to_dict(r) for r in rows]
    # 缓存最近 MSG_CACHE_COUNT 条：会话继续增长时旧部分仍以 DB 为准；
    # Redis 缓存只缩短首读路径，写入后即失效，正确性由失效保证。
    await _cache_set(cache_key, json.dumps(msgs[-MSG_CACHE_COUNT:], ensure_ascii=False), MSG_CACHE_TTL)
    return msgs


def _row_to_dict(r) -> dict:
    d = {
        "role": r["role"],
        "content": r["content"],
        "id": r["msg_id"] or None,
        "created_at": r["created_at"],
        "rowid": r["id"],
    }
    if r["tool_calls"]:
        d["tool_calls"] = json.loads(r["tool_calls"])
    if r["tool_call_id"]:
        d["tool_call_id"] = r["tool_call_id"]
    if r["usage"]:
        d["usage"] = json.loads(r["usage"])
    return d


def _dict_to_row(session_id: str, m: dict) -> tuple:
    return (
        session_id,
        m.get("id") or "",
        m.get("role", "user"),
        m.get("content", ""),
        json.dumps(m.get("tool_calls") or [], ensure_ascii=False),
        m.get("tool_call_id", ""),
        json.dumps(m.get("usage") or {}, ensure_ascii=False) if m.get("usage") else "",
        int(m.get("created_at") or int(__import__("time").time())),
    )


async def _invalidate_messages(session_id: str, user_id: int | None = None) -> None:
    await _cache_del(f"session:{session_id}:messages")
    if user_id is not None:
        await _cache_del(await _list_cache_key(user_id))


async def add_messages(session_id: str, messages: list[dict]) -> None:
    await db.execute_many(
        "INSERT INTO messages (session_id, msg_id, role, content, tool_calls, tool_call_id, usage, created_at) "
        "VALUES (?,?,?,?,?,?,?,?)",
        [_dict_to_row(session_id, m) for m in messages],
    )
    await db.execute("UPDATE sessions SET updated_at=? WHERE id=?", (int(__import__("time").time()), session_id))
    await _invalidate_messages(session_id)


async def replace_messages(session_id: str, messages: list[dict]) -> None:
    """原子替换会话消息：先删后插与 updated_at 更新在同一个事务内完成。

    任一步失败整体回滚，崩溃不留「删了一半/空会话」的半状态（IMPROVEMENTS.md A2）。
    """
    rows = [_dict_to_row(session_id, m) for m in messages]
    now = int(__import__("time").time())

    ops: list[tuple] = [
        ("DELETE FROM messages WHERE session_id=?", (session_id,)),
    ]
    if rows:
        ops.append((
            "INSERT INTO messages (session_id, msg_id, role, content, tool_calls, tool_call_id, usage, created_at) "
            "VALUES (?,?,?,?,?,?,?,?)",
            rows,
            True,
        ))
    ops.append(("UPDATE sessions SET updated_at=? WHERE id=?", (now, session_id)))

    await db.execute_tx(ops)
    await _invalidate_messages(session_id)


async def get_session_owner(session_id: str) -> int | None:
    rows = await db.run("SELECT user_id FROM sessions WHERE id=?", (session_id,))
    return rows[0]["user_id"] if rows else None


async def get_session_summary(session_id: str) -> tuple[str, str] | None:
    """读取会话摘要缓存。返回 (summary, fold_key)；fold_key 为摘要覆盖到的
    最后一条折叠消息的稳定 msg_id（替代易漂移的 rowid，见 IMPROVEMENTS.md A3）。"""
    rows = await db.run(
        "SELECT summary, fold_msg_id FROM sessions WHERE id=?", (session_id,)
    )
    if not rows:
        return None
    summary = rows[0]["summary"]
    fold_key = rows[0]["fold_msg_id"]
    if not summary:
        return None
    return summary, fold_key


async def set_session_summary(session_id: str, summary: str, fold_key: str) -> None:
    """落库本轮生成的摘要与其折叠截止 msg_id（下次折叠点未前移时不重复生成）。"""
    await db.execute(
        "UPDATE sessions SET summary=?, fold_msg_id=? WHERE id=?",
        (summary, fold_key, session_id),
    )