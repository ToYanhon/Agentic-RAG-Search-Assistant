"""共享 httpx.AsyncClient（连接池复用，避免每次调用重建）。

改造前工具/下载/技能加载每次调用都 new AsyncClient，无连接复用（IMPROVEMENTS.md A10）。
此处提供进程内单例：连接池 + keepalive 复用；headers/timeout 仍按请求显式传入
（内部 token 按调用取得，可随轮换更新）。trust_env=False 防系统代理干扰。
"""

import asyncio

import httpx

_client: httpx.AsyncClient | None = None
_lock = asyncio.Lock()

DEFAULT_TIMEOUT = 15.0


async def get_http_client() -> httpx.AsyncClient:
    """返回进程内共享 AsyncClient（惰性创建，线程/协程安全）。"""
    global _client
    if _client is None:
        async with _lock:
            if _client is None:
                _client = httpx.AsyncClient(
                    timeout=DEFAULT_TIMEOUT,
                    trust_env=False,
                    limits=httpx.Limits(
                        max_connections=100,
                        max_keepalive_connections=20,
                    ),
                )
    return _client


async def close_http_client() -> None:
    """应用关闭时释放连接池。"""
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None
