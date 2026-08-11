"""技能体系 — 全局技能目录 + 云盘文件式技能，触发式注入 + 定时热加载。

对外主要入口：
  - activated_skills_context(text, user_id)：返回本轮触发技能的指令块（供提示词注入）
  - ensure_user_skills(uid)：聊天前懒刷新该用户云盘技能
  - registry：全局技能注册表（含可执行工具）
"""

from app.core.skills.global_loader import scan_global, sync_global
from app.core.skills.manifest import Skill, parse_skill_md
from app.core.skills.registry import registry
from app.core.skills.user_loader import ensure_user_skills
from app.prompt.prompts import SKILL_CONTEXT_GUARD


def activated_skills_context(text: str, user_id: int = 0) -> str:
    """返回 text 触发（或常驻）技能的指令块；无命中返回空串。"""
    from app.config import settings
    from app.core.metrics import skill_activations

    skills = registry.activated(text, user_id)
    if not skills:
        return ""
    parts = [SKILL_CONTEXT_GUARD]
    budget = settings.skill_context_max_bytes
    for s in skills:
        block = f"### 技能：{s.name}\n{s.description}\n{s.prompt}".strip()
        if budget <= 0:
            break
        parts.append(block[:budget])
        budget -= len(block)
        skill_activations.inc(1, {"skill_id": s.id, "origin": s.origin})
    return "\n\n".join(parts)
