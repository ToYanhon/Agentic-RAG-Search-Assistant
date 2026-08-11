"""SkillManager — 云盘技能懒刷新 + 触发匹配生成 skills_context。

替代原 chat.py 中零散的两步：ensure_user_skills + activated_skills_context。
"""

from app.core.skills import activated_skills_context, ensure_user_skills


class SkillManager:
    async def activate(self, user_id: int, message: str) -> str:
        """回合开始调用：懒刷新该用户云盘技能，返回命中触发词的技能指令块。"""
        if user_id > 0:
            await ensure_user_skills(user_id)
        return activated_skills_context(message, user_id)
