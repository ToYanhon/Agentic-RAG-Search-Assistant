"""全局技能加载器 — 扫描 SKILLS_DIR。

目录形态：<skill>/SKILL.md [+ tools.py]，tools.py 内的 @tool/@safe_tool 会被
注册为该技能的可执行工具（admin 可信，随部署分发，需代码评审）；
扁平形态：<skill>.md（纯指令式，无代码）。
"""

import importlib.util
import logging
import sys
from pathlib import Path

from langchain_core.tools import BaseTool

from app.config import settings
from app.core.skills.manifest import Skill, parse_skill_md
from app.core.skills.registry import registry

logger = logging.getLogger(__name__)


def _parse_skill_from(
    text: str,
    origin: str,
    source: str,
    mtime: float,
    user_id: int = 0,
) -> Skill | None:
    """解析 SKILL.md 文本为 Skill；无效或超限返回 None。"""
    parsed = parse_skill_md(text)
    if not parsed:
        return None
    m = parsed["manifest"]
    if len(text.encode("utf-8", "ignore")) > settings.skill_max_bytes:
        logger.warning("skill %s exceeds size limit, skipped", m["id"])
        return None
    return Skill(
        id=m["id"],
        name=m["name"],
        description=m["description"],
        triggers=m["triggers"],
        prompt=parsed["body"],
        version=m["version"],
        tools=m["tools"],
        always=m["always"],
        origin=origin,
        user_id=user_id,
        source=source,
        mtime=mtime,
    )


def _load_skill_tools(skill_dir: Path) -> list[BaseTool]:
    """导入技能目录中的 tools.py，收集 BaseTool 实例；失败降级为空。"""
    tools_path = skill_dir / "tools.py"
    if not tools_path.is_file():
        return []
    try:
        spec = importlib.util.spec_from_file_location(
            f"clouddrive_skill_{skill_dir.name}", tools_path
        )
        if spec is None or spec.loader is None:
            return []
        mod = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = mod
        spec.loader.exec_module(mod)
        return [
            getattr(mod, n) for n in dir(mod) if isinstance(getattr(mod, n), BaseTool)
        ]
    except Exception:  # noqa: BLE001 - 单个技能失败不影响其余技能加载
        logger.exception("failed to load skill tools from %s", tools_path)
        return []


def scan_global() -> list[Skill]:
    """扫描全局技能目录，返回全部有效技能（不写 registry）。"""
    base = Path(settings.skills_dir)
    out: list[Skill] = []
    if not base.is_dir():
        return out
    for entry in sorted(base.iterdir()):
        # 下划线前缀（如 _template）视为模板/隐藏技能，不加载
        if entry.name.startswith("_"):
            continue
        try:
            if entry.is_dir():
                md = entry / "SKILL.md"
                if not md.is_file():
                    continue
                s = _parse_skill_from(
                    md.read_text(encoding="utf-8", errors="ignore"),
                    "global",
                    str(entry),
                    md.stat().st_mtime,
                )
                if s:
                    s.executable_tools = _load_skill_tools(entry)
                    out.append(s)
            elif entry.suffix.lower() == ".md":
                s = _parse_skill_from(
                    entry.read_text(encoding="utf-8", errors="ignore"),
                    "global",
                    str(entry),
                    entry.stat().st_mtime,
                )
                if s:
                    out.append(s)
        except OSError:
            logger.exception("skill scan entry failed: %s", entry)
    return out


def sync_global() -> None:
    """全量同步全局技能到 registry（启动与定时扫描调用，幂等）。"""
    seen: set[str] = set()
    for s in scan_global():
        registry.upsert(s)
        seen.add(s.key())
    for key in registry.global_keys():
        if key not in seen:
            registry.remove(key)
