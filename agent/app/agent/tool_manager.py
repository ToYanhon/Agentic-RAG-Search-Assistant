"""ToolManager — 工具注册、worker 子集、执行与错误兜底。

- 注册：基础工具（tools.py）+ 全局技能可执行工具（registry，按 revision 热刷新）
- worker 子集：委托 workers.worker_tools（general 含记忆工具 + 技能工具）
- execute：按名调用 BaseTool，异常转错误字符串（safe_tool 之外的第二层兜底），并打点
"""

import logging

from app.agent.tools import tools as BASE_TOOLS
from app.agent.workers import worker_tools
from app.core.metrics import now, tool_calls, tool_latency
from app.core.skills.registry import registry

logger = logging.getLogger(__name__)


class ToolManager:
    def __init__(self) -> None:
        self._tools: list = []
        self._rev: int = -1

    def _refresh(self) -> None:
        """技能热加载（registry revision 变化）后重建全量工具列表。"""
        rev = registry.revision
        if rev != self._rev:
            self._tools = list(BASE_TOOLS) + registry.executable_tools()
            self._rev = rev

    def all(self) -> list:
        """全量工具 = 基础工具 + 全局技能可执行工具。"""
        self._refresh()
        return list(self._tools)

    def for_worker(self, worker: str) -> list:
        """某 worker 可用的工具子集（workers.worker_tools 内部已含技能工具与记忆工具）。"""
        self._refresh()
        return list(worker_tools(worker))

    def get(self, name: str):
        """按工具名查找；未注册返回 None。"""
        return next((t for t in self.all() if t.name == name), None)

    async def execute(self, name: str, args: dict) -> str:
        """执行工具并返回字符串结果；异常转错误字符串，不向流程抛错。"""
        tool = self.get(name)
        if tool is None:
            return f"[工具不存在: {name}]"
        labels = {"name": name}
        start = now()
        try:
            result = await tool.ainvoke(args or {})
            tool_calls.inc(1, {**labels, "status": "success"})
            return result if isinstance(result, str) else str(result)
        except Exception as e:  # noqa: BLE001 - 工具层兜底一切异常
            tool_calls.inc(1, {**labels, "status": "error"})
            logger.exception("tool execute failed: %s", name)
            return f"[工具执行失败: {type(e).__name__}: {e}]"
        finally:
            tool_latency.observe(now() - start, labels)
