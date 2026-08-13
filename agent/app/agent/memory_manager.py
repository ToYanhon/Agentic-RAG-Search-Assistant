"""MemoryManager — 用户长期记忆的加载/注入与回合后后台提炼。

记忆工具（save/forget/get）由 general worker 的工具集调用，本管理器负责
框架侧能力：回合开始加载记忆注入提示词、回合结束后台提炼新事实。
"""

import logging

from app.core.bg_tasks import run_bg
from app.service import memory_service

logger = logging.getLogger(__name__)


class MemoryManager:
    async def load(self, user_id: int) -> list[str]:
        """加载该用户活跃记忆（供 worker 系统提示词注入）。"""
        return await memory_service.get_memory(user_id)

    async def save(self, user_id: int, fact: str) -> str:
        return await memory_service.add_memory_smart(user_id, fact)

    async def forget(self, user_id: int, keyword: str) -> str:
        removed = await memory_service.forget_memory(user_id, keyword)
        return f"已删除 {removed} 条相关记忆" if removed else "没有找到相关的长期记忆"

    async def list(self, user_id: int) -> str:
        memories = await memory_service.get_memory(user_id)
        if not memories:
            return "暂无长期记忆"
        return "\n".join(f"- {m}" for m in memories)

    def schedule_extraction(self, user_id: int, conversation: str, session_id: str) -> None:
        """回合后后台提炼长期记忆（不阻塞 SSE 完成；走统一骨架重试，最终失败仅日志）。"""
        try:
            run_bg(
                lambda: memory_service.extract_memory_from_conversation(
                    user_id, conversation, session_id
                ),
                name="memory_extract",
            )
        except RuntimeError:
            logger.warning("no running loop, skip memory extraction")
