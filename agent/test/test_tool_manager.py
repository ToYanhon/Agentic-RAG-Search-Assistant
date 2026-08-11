"""ToolManager 测试 — 注册 / worker 子集 / 执行与错误兜底。"""

import pytest

from app.agent.tool_manager import ToolManager


@pytest.fixture
def tm():
    return ToolManager()


def test_worker_subsets(tm):
    file = {t.name for t in tm.for_worker("file")}
    web = {t.name for t in tm.for_worker("web")}
    general = {t.name for t in tm.for_worker("general")}
    assert "search_files" in file
    assert "web_search" in web
    # 记忆工具并入 general
    for name in ["save_memory", "forget_memory", "get_memory"]:
        assert name in general
    assert "get_storage_usage" in general
    assert "search_files" not in general
    assert "web_search" not in file


def test_get_known_and_unknown(tm):
    assert tm.get("no_such_tool") is None
    assert tm.get("web_search") is not None


@pytest.mark.asyncio
async def test_execute_unknown(tm):
    assert await tm.execute("no_such_tool", {}) == "[工具不存在: no_such_tool]"


@pytest.mark.asyncio
async def test_execute_known_tool(monkeypatch):
    import app.service.memory_service as ms

    async def fake(uid):
        return ["偏好英文"]

    monkeypatch.setattr(ms, "get_memory", fake)
    tm = ToolManager()
    result = await tm.execute("get_memory", {})
    assert result == "- 偏好英文"


@pytest.mark.asyncio
async def test_execute_wraps_failure(monkeypatch):
    import app.service.memory_service as ms

    async def boom(uid):
        raise RuntimeError("redis down")

    monkeypatch.setattr(ms, "get_memory", boom)
    tm = ToolManager()
    result = await tm.execute("get_memory", {})
    assert "工具执行失败" in result
