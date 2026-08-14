"""Agent 内部认证 token — 从 Redis 读取 Go 定期轮换的 token，带内存缓存。

缓存策略:
  Go 每 15min 轮换一次（TTL 30min）。非空 token 缓存 8min（< 轮换间隔），
  配合工具层 401 时的缓存重置重试，可避免「缓存过期后仍持旧 token」的间隙。
  空值（旋转空窗期 Redis 暂无 key）仅缓存 2s，避免反复打 Redis 且很快自愈，
  不污染长缓存（IMPROVEMENTS.md A7）。
"""

import time

from app.config import settings
from app.core.redis_pool import get_redis

REDIS_KEY = "internal:agent:token"
CACHE_TTL = 8 * 60  # 8min，略小于 Go 的 15min 轮换间隔
EMPTY_CACHE_TTL = 2  # 空值仅缓存 2s

_token: str = ""
_token_expires: float = 0.0


async def get_internal_token(force_refresh: bool = False) -> str:
    global _token, _token_expires

    now = time.time()
    if not force_refresh and now < _token_expires:
        return _token

    r = get_redis()
    raw = await r.get(REDIS_KEY)
    _token = raw if isinstance(raw, str) else ""
    _token_expires = now + (CACHE_TTL if _token else EMPTY_CACHE_TTL)
    return _token


async def reset_token_cache():
    global _token, _token_expires
    _token = ""
    _token_expires = 0.0
