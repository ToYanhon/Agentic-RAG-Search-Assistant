"""技能注册表 — 全局/用户命名空间隔离、按用户触发匹配、热更新版本号。

key 规则（见 Skill.key）：
  - 全局技能：g:<id>
  - 用户技能：u:<uid>:<id>（同 id 用户技能覆盖全局）
revision 单调递增，供 LLM 工具绑定层感知技能热加载并重建缓存。
"""

import logging
import re
from threading import Lock

from app.core.skills.manifest import Skill

logger = logging.getLogger(__name__)


class SkillRegistry:
    def __init__(self) -> None:
        self._skills: dict[str, Skill] = {}
        self._lock = Lock()
        self._revision = 0

    @property
    def revision(self) -> int:
        with self._lock:
            return self._revision

    def upsert(self, skill: Skill) -> None:
        with self._lock:
            self._skills[skill.key()] = skill
            self._revision += 1

    def remove(self, key: str) -> None:
        with self._lock:
            if self._skills.pop(key, None) is not None:
                self._revision += 1

    def get(self, key: str) -> Skill | None:
        with self._lock:
            return self._skills.get(key)

    def all(self) -> list[Skill]:
        with self._lock:
            return list(self._skills.values())

    def global_keys(self) -> list[str]:
        """返回全局技能的注册表 key（供全量同步清理用）。"""
        with self._lock:
            return [k for k in self._skills if k.startswith("g:")]

    def executable_tools(self) -> list:
        """全部全局技能注册的可执行工具（不包含云盘技能）。"""
        with self._lock:
            out: list = []
            for s in self._skills.values():
                if s.origin == "global" and s.executable_tools:
                    out.extend(s.executable_tools)
            return out

    def activated(self, text: str, user_id: int = 0) -> list[Skill]:
        """返回 text 触发的技能（常驻 always 始终返回），按用户隔离。"""
        with self._lock:
            skills = list(self._skills.values())
        matched: list[Skill] = [s for s in skills if s.always]
        for s in skills:
            if s.always:
                continue
            if s.origin == "user" and s.user_id != user_id:
                continue
            if any(
                re.search(re.escape(t), text, re.IGNORECASE) for t in s.triggers
            ):
                matched.append(s)
        return matched


registry = SkillRegistry()
