"""共享 httpx 客户端测试 — 单例与关闭（A10 连接复用）。"""

import asyncio

from app.core import http


def test_get_http_client_is_singleton():
    async def run():
        c1 = await http.get_http_client()
        c2 = await http.get_http_client()
        assert c1 is c2
        assert c1._transport is not None  # 连接池已初始化
        await http.close_http_client()

    asyncio.run(run())


def test_close_resets_singleton():
    async def run():
        c1 = await http.get_http_client()
        await http.close_http_client()
        c2 = await http.get_http_client()
        assert c1 is not c2
        await http.close_http_client()

    asyncio.run(run())
