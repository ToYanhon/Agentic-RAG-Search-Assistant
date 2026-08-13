"""Worker 规格 — 专项助手的能力边界（工具子集 + 展示名）。

技能体系的可执行工具（全局技能 tools.py）追加到 general worker；
记忆工具并入 general（记忆管理不再路由独立 agent，由 general + MemoryManager 承担）。
"""

from app.agent import tools as T

# worker id → 基础工具子集
WORKER_TOOLS: dict[str, list] = {
    "file": T.FILE_TOOLS,
    "web": T.WEB_TOOLS,
    "general": T.GENERAL_TOOLS + T.MEMORY_TOOLS,
}

# worker id → 展示名（Supervisor 转移工具描述、前端 route 事件）
WORKER_NAMES: dict[str, str] = {
    "file": "文件助手",
    "web": "联网助手",
    "general": "通用助手",
}

# worker id → 一句话能力摘要（转移工具 schema 描述用，供 Supervisor 路由决策）
WORKER_TAGLINES: dict[str, str] = {
    "file": "查找/阅读/总结/浏览网盘文件，语义检索文件内容；创建、编辑、覆盖文本文件",
    "web": "搜索互联网获取实时信息",
    "general": "日常问答、记忆管理、存储空间查询等通用任务",
}


def worker_tools(worker: str) -> list:
    """返回某 worker 的全部可用工具（general 追加全局技能的可执行工具）。"""
    base = list(WORKER_TOOLS[worker])
    if worker == "general":
        from app.core.skills.registry import registry

        base.extend(registry.executable_tools())
    return base
