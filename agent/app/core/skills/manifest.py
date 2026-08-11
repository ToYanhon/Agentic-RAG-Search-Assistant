"""技能清单解析 — SKILL.md（YAML frontmatter + 指令正文）。

统一技能描述格式：
  ---
  name: <技能 ID，必填>
  description: <一句话说明，供调度与展示>
  trigger: [关键词1, 关键词2]   # 触发词，命中用户消息即激活（可省，配合 always）
  tools: [内置工具名, ...]      # 云盘技能允许调用的内置工具白名单（可选）
  always: true                  # 常驻，无需触发（可选）
  version: "1"                  # 可选
  ---
  <指令正文：注入到触发时的工作 agent 系统提示词>

两类来源：
  - 全局技能（agent/skills/，admin 可信）：可带 tools.py 注册可执行工具
  - 云盘技能（用户网盘 skills 文件夹的 .md）：纯指令式，永不执行用户代码
"""

import re
from dataclasses import dataclass, field

import yaml

# --- 分隔的 frontmatter 区块 + 正文
FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---\s*\n?(.*)$", re.DOTALL)


@dataclass
class Skill:
    id: str
    name: str
    description: str = ""
    triggers: list[str] = field(default_factory=list)
    prompt: str = ""
    version: str = "1"
    tools: list[str] = field(default_factory=list)
    always: bool = False
    origin: str = "global"  # global | user
    user_id: int = 0  # origin=user 时的归属用户
    source: str = ""
    mtime: float = 0.0
    executable_tools: list = field(default_factory=list)  # 仅全局技能加载 tools.py

    def key(self) -> str:
        """注册表 key：全局 g:<id>；用户 u:<uid>:<id>（同 id 用户覆盖全局）。"""
        return (
            f"g:{self.id}"
            if self.origin == "global"
            else f"u:{self.user_id}:{self.id}"
        )

    @property
    def is_executable(self) -> bool:
        return self.origin == "global" and bool(self.executable_tools)


def parse_skill_md(text: str) -> dict:
    """解析 SKILL.md，返回 {manifest: {...}, body: str}；frontmatter 缺失/无效返回 {}。"""
    m = FRONTMATTER_RE.match(text)
    if not m:
        return {}
    try:
        meta = yaml.safe_load(m.group(1))
    except yaml.YAMLError:
        return {}
    if not isinstance(meta, dict):
        return {}
    name = str(meta.get("name") or "").strip()
    if not name:
        return {}
    triggers = meta.get("trigger") or meta.get("triggers") or []
    if isinstance(triggers, str):
        triggers = [triggers]
    tools = meta.get("tools") or []
    if isinstance(tools, str):
        tools = [tools]
    body = (m.group(2) or "").strip()
    return {
        "manifest": {
            "id": str(meta.get("id") or name).strip(),
            "name": name,
            "description": str(meta.get("description") or "").strip(),
            "triggers": [str(t).strip() for t in triggers if str(t).strip()],
            "tools": [str(t).strip() for t in tools if str(t).strip()],
            "always": bool(meta.get("always") or False),
            "version": str(meta.get("version") or "1").strip(),
        },
        "body": body,
    }
