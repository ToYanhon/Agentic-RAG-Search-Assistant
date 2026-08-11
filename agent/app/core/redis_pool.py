"""统一 Redis 连接池 — 全项目共享单例，避免各模块重复建池。

用法:
    from app.core.redis_pool import get_redis
    r = get_redis()
    await r.get("key")
"""

import redis.asyncio as aioredis

from app.config import settings

_pool = aioredis.ConnectionPool.from_url(
    settings.redis_url, decode_responses=True, max_connections=32
)


def get_redis() -> aioredis.Redis:
    """从共享连接池获取一个 Redis 客户端实例。"""
    return aioredis.Redis(connection_pool=_pool)
