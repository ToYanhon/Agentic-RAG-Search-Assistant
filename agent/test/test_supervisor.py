"""Supervisor 路由测试 — transfer 工具构造 + worker 工具子集（自建框架，纯逻辑）。

完整回合链路（supervisor → worker → 收尾）在 test_workflow.py 覆盖。
"""

import asyncio

from app.agent.workers import WORKER_TOOLS
from app.agent.workflow import TRANSFER_TOOLS, _transfer_tool


def test_transfer_tools_cover_all_workers():
    names = {t.name for t in TRANSFER_TOOLS}
    assert names == {
        "transfer_to_file",
        "transfer_to_web",
        "transfer_to_general",
    }


def test_transfer_tool_returns_target():
    t = _transfer_tool("web")
    assert t.name == "transfer_to_web"
    assert asyncio.run(t.ainvoke({})) == "web"
    # 描述携带能力摘要，Supervisor 依赖 schema 而非提示词枚举
    assert "联网" in t.description


def test_worker_tool_partition_covers_base_tools():
    """各 worker 工具子集覆盖全部基础工具（记忆工具并入 general）。"""
    from app.agent import tools as tools_mod
    from app.agent.workers import worker_tools

    file_tools = {t.name for t in worker_tools("file")}
    web_tools = {t.name for t in worker_tools("web")}
    general_tools = {t.name for t in worker_tools("general")}
    base = {t.name for t in tools_mod.tools}

    # 文件/联网互不重叠
    assert not (file_tools & web_tools)
    # 记忆工具归入 general
    for name in ["save_memory", "forget_memory", "get_memory"]:
        assert name in general_tools
    assert name not in file_tools and name not in web_tools
    # 全部分区恰好等于基础工具全集
    assert (file_tools | web_tools | general_tools) == base


def test_worker_tools_defined_for_every_worker():
    from app.agent.workers import WORKER_NAMES, worker_tools

    for worker in WORKER_NAMES:
        assert worker_tools(worker), f"{worker} 工具子集为空"
    assert set(WORKER_TOOLS) == set(WORKER_NAMES)
