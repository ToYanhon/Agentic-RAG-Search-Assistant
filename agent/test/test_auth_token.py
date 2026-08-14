"""auth_token 内部 token 缓存测试（A7）：非空缓存 8min、空值仅缓存 2s、force_refresh 绕过。"""

import asyncio
import time

import pytest

from app.auth_token import get_internal_token, reset_token_cache


@pytest.fixture(autouse=True)
def _reset():
    asyncio.run(reset_token_cache())
    yield


class _FakeRedis:
    def __init__(self, *values):
        self._values = list(values)
        self.calls = 0

    async def get(self, key):
        self.calls += 1
        v = self._values[min(self.calls - 1, len(self._values) - 1)]
        return v


def test_nonempty_token_cached(monkeypatch):
    import app.auth_token as at

    r1 = _FakeRedis("tok1")
    r2 = _FakeRedis("tok2")
    monkeypatch.setattr(at, "get_redis", lambda: r1)
    assert asyncio.run(get_internal_token()) == "tok1"
    monkeypatch.setattr(at, "get_redis", lambda: r2)
    # 缓存有效期内命中 → 不重读 Redis
    assert asyncio.run(get_internal_token()) == "tok1"
    assert r2.calls == 0


def test_empty_token_uses_short_ttl(monkeypatch):
    import app.auth_token as at

    fake = _FakeRedis(None)
    monkeypatch.setattr(at, "get_redis", lambda: fake)
    assert asyncio.run(get_internal_token()) == ""
    # 空值过期点 ≈ now + 2s（远小于 CACHE_TTL=480s），不污染长缓存
    assert 0 < at._token_expires - time.time() <= at.EMPTY_CACHE_TTL + 0.5


def test_force_refresh_bypasses_cache(monkeypatch):
    import app.auth_token as at

    r1 = _FakeRedis("old")
    r2 = _FakeRedis("new")
    monkeypatch.setattr(at, "get_redis", lambda: r1)
    assert asyncio.run(get_internal_token()) == "old"
    monkeypatch.setattr(at, "get_redis", lambda: r2)
    # 401 路径强制刷新 → 绕过缓存重读
    assert asyncio.run(get_internal_token(force_refresh=True)) == "new"
    assert r2.calls == 1
