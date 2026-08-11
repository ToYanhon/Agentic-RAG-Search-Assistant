"""云盘技能加载器 — 从用户网盘 skills 文件夹拉取 .md 技能。

安全边界：用户上传的技能只作为指令 + 工具白名单注入提示词，
绝不执行任何用户代码、不注册可执行工具（executable_tools 恒为空）。
按用户 TTL 懒刷新（聊天时触发），失败不影响主链路。
"""

import asyncio
import logging
import time

import httpx

from app.auth_token import get_internal_token
from app.config import settings
from app.core.skills.global_loader import _parse_skill_from
from app.core.skills.registry import registry

logger = logging.getLogger(__name__)


class _UserSkillsCache:
    """按用户缓存已加载技能 key 与时间戳（TTL 过期后懒刷新）。"""

    def __init__(self) -> None:
        self._entries: dict[int, tuple[float, list[str]]] = {}
        self._lock = asyncio.Lock()

    async def fresh_keys(self, uid: int) -> list[str] | None:
        """返回未过期的技能 key 列表；无缓存或已过期返回 None。"""
        async with self._lock:
            e = self._entries.get(uid)
            if not e:
                return None
            ts, keys = e
            if time.time() - ts > settings.user_skills_ttl_sec:
                return None
            return list(keys)

    async def previous_keys(self, uid: int) -> list[str]:
        """返回上次加载的技能 key 列表（无视 TTL），供清理失效技能。"""
        async with self._lock:
            e = self._entries.get(uid)
            return list(e[1]) if e else []

    async def set(self, uid: int, keys: list[str]) -> None:
        async with self._lock:
            self._entries[uid] = (time.time(), list(keys))


cache = _UserSkillsCache()


async def _get_json(
    client: httpx.AsyncClient, path: str, uid: int
) -> dict | list | None:
    try:
        resp = await client.get(
            f"{settings.backend_url}{path}", params={"user_id": uid}
        )
        if resp.status_code == 200:
            return resp.json().get("data")
    except (httpx.HTTPError, ValueError):
        logger.exception("skill folder api failed: %s", path)
    return None


async def _find_skill_files(uid: int) -> list[dict]:
    """找到用户 skills 文件夹并列出其中 .md 文件 [{id, name}]。"""
    token = await get_internal_token()
    async with httpx.AsyncClient(
        headers={"X-Agent-Token": token}, timeout=10.0, trust_env=False
    ) as client:
        roots = await _get_json(client, "/folders/root", uid)
        if not isinstance(roots, list):
            return []
        folder = next(
            (f for f in roots if f.get("name") == settings.user_skills_folder),
            None,
        )
        if not folder:
            return []
        data = await _get_json(client, f"/folders/{folder.get('id')}", uid)
        if not isinstance(data, dict):
            return []
        return [
            {"id": f.get("id"), "name": f.get("name", "")}
            for f in (data.get("files") or [])
            if str(f.get("name", "")).lower().endswith(".md")
        ]


async def _load_user_skills(uid: int) -> list[str]:
    """拉取并注册该用户全部云盘技能；返回已注册的 registry key 列表。"""
    keys: list[str] = []
    for f in await _find_skill_files(uid):
        fid, name = f["id"], f["name"]
        try:
            token = await get_internal_token()
            async with httpx.AsyncClient(
                headers={"X-Agent-Token": token}, timeout=10.0, trust_env=False
            ) as client:
                resp = await client.get(
                    f"{settings.backend_url}/files/{fid}/download",
                    params={"user_id": uid},
                )
                if resp.status_code != 200:
                    continue
                content = resp.content
                if len(content) > settings.skill_max_bytes:
                    logger.warning("user skill %s too large, skipped", name)
                    continue
                s = _parse_skill_from(
                    content.decode("utf-8", errors="ignore"),
                    "user",
                    f"user:{uid}:{name}",
                    0,
                    user_id=uid,
                )
                if not s:
                    continue
                registry.upsert(s)
                keys.append(s.key())
        except (httpx.HTTPError, ValueError):
            logger.exception("user skill load failed: %s", name)
    return keys


async def ensure_user_skills(uid: int) -> None:
    """聊天前调用：按 TTL 懒刷新该用户云盘技能；失败不影响主链路。"""
    if uid <= 0:
        return
    try:
        if await cache.fresh_keys(uid) is not None:
            return
        previous = await cache.previous_keys(uid)
        keys = await _load_user_skills(uid)
        for k in previous:
            if k not in keys:
                registry.remove(k)
        await cache.set(uid, keys)
        logger.info("user skills refreshed", extra={"user_id": uid, "count": len(keys)})
    except Exception:
        logger.exception("ensure_user_skills failed")
