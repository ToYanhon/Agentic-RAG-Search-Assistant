"""工具故障恢复测试 — safe_tool 兜底 / HTTP 重试 / LLM 重试。"""

from typing import ClassVar, Protocol

import httpx
import pytest
from app.agent.llm_utils import ainvoke_with_retry
from app.agent.tools import _http_get, safe_tool


class Invokable(Protocol):
    async def ainvoke(self, messages) -> str: ...


class FakeLLM:
    def __init__(self, failures: int):
        self.failures = failures
        self.calls = 0

    async def ainvoke(self, input, config=None, **kwargs):
        self.calls += 1
        if self.calls <= self.failures:
            raise httpx.ConnectError("connect failed")
        return "ok"


@pytest.mark.asyncio
async def test_retry_eventually_succeeds():
    llm: Invokable = FakeLLM(failures=2)
    result = await ainvoke_with_retry(llm, [])
    assert result == "ok"
    assert llm.calls == 3


@pytest.mark.asyncio
async def test_retry_gives_up_after_max():
    llm: Invokable = FakeLLM(failures=99)
    with pytest.raises(httpx.ConnectError):
        await ainvoke_with_retry(llm, [])
    assert llm.calls == 3


class FailTool:
    @safe_tool
    async def boom(self):
        raise RuntimeError("backend exploded")


@pytest.mark.asyncio
async def test_safe_tool_catches_exception():
    t = FailTool()
    result = await t.boom()
    assert isinstance(result, str)
    assert "工具执行失败" in result
    assert "backend exploded" in result


@pytest.mark.asyncio
async def test_http_get_returns_none_on_connection_error(monkeypatch):
    async def fake_token():
        return "tok"

    monkeypatch.setattr("app.agent.tools.get_internal_token", fake_token)
    monkeypatch.setattr(
        "app.agent.tools.settings.backend_url", "http://127.0.0.1:1"
    )  # 不可达端口
    result = await _http_get("/x", {})
    assert result is None


@pytest.mark.asyncio
async def test_http_get_success(monkeypatch):
    async def fake_token():
        return "tok"

    monkeypatch.setattr("app.agent.tools.get_internal_token", fake_token)

    from app.agent import tools as tools_mod

    original = httpx.AsyncClient

    class FakeResponse:
        status_code = 200
        headers: ClassVar = {"content-type": "text/plain"}

        def json(self):
            return {"data": {"files": [1]}}

    class FakeClient:
        def __init__(self, *a, **k):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def get(self, url, params=None):
            return FakeResponse()

    tools_mod.httpx.AsyncClient = FakeClient
    try:
        result = await _http_get("/x", {})
        assert result is not None and result.status_code == 200
    finally:
        tools_mod.httpx.AsyncClient = original
