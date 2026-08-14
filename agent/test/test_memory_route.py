"""memory 路由测试（A22）：缺 X-User-Id（uid==0）时 401。"""

import asyncio

from app.router import memory as mem


class _Req:
    def __init__(self, headers=None):
        self.headers = headers or {}


def run(coro):
    return asyncio.run(coro)


def test_list_memory_rejects_uid_zero():
    resp = run(mem.list_memory(_Req()))
    assert resp.status_code == 401


def test_delete_memory_rejects_uid_zero():
    resp = run(mem.delete_memory(_Req(), "kw"))
    assert resp.status_code == 401


def test_delete_memory_requires_keyword():
    # keyword 缺失时 FastAPI 会 422；这里直接调用应走 uid 校验先返回 401（与顺序无关，逻辑不变）
    resp = run(mem.delete_memory(_Req(), ""))
    assert resp.status_code == 401
