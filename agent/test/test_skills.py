"""技能体系测试 — SKILL.md 解析 / 注册表 / 触发注入 / 全局扫描与可执行工具加载。

全部 mock 或临时目录，无需基础设施。
"""

import asyncio
import textwrap

from app import config
from app.core.skills import activated_skills_context
from app.core.skills.global_loader import _load_skill_tools, scan_global, sync_global
from app.core.skills.manifest import Skill, parse_skill_md
from app.core.skills.registry import SkillRegistry

VALID_MD = textwrap.dedent(
    """\
    ---
    name: resume-analyzer
    description: 分析简历
    trigger: [简历, resume]
    tools: [read_file_content]
    version: "2"
    ---
    输出结构化简历评估。
    """
)


def test_parse_skill_md_valid():
    r = parse_skill_md(VALID_MD)
    assert r["manifest"]["id"] == "resume-analyzer"
    assert r["manifest"]["triggers"] == ["简历", "resume"]
    assert r["manifest"]["tools"] == ["read_file_content"]
    assert r["manifest"]["version"] == "2"
    assert r["body"] == "输出结构化简历评估。"


def test_parse_skill_md_trigger_as_string():
    r = parse_skill_md("---\nname: x\ntrigger: 简历\n---\n正文")
    assert r["manifest"]["triggers"] == ["简历"]


def test_parse_skill_md_invalid():
    assert parse_skill_md("no frontmatter") == {}
    assert parse_skill_md("---\nname:\n---\nbody") == {}
    assert parse_skill_md("---\n: bad yaml [\n---\nbody") == {}


def test_skill_key_namespace():
    g = Skill(id="a", name="A", origin="global")
    u = Skill(id="a", name="A", origin="user", user_id=7)
    assert g.key() == "g:a"
    assert u.key() == "u:7:a"


def test_registry_activated_by_keyword():
    reg = SkillRegistry()
    reg.upsert(Skill(id="s1", name="简历", triggers=["简历"], origin="global"))
    reg.upsert(Skill(id="always", name="常驻", always=True, origin="global"))
    hit = reg.activated("帮我看看简历", user_id=1)
    assert {s.id for s in hit} == {"s1", "always"}
    assert reg.activated("随便聊聊", user_id=1)[0].id == "always"


def test_registry_user_isolation():
    reg = SkillRegistry()
    reg.upsert(Skill(id="me", name="我的", triggers=["简历"], origin="user", user_id=7))
    assert reg.activated("看看简历", user_id=1) == []
    assert reg.activated("看看简历", user_id=7) != []
    assert reg.executable_tools() == []  # 用户技能永不携带可执行工具


def test_registry_revision_increments():
    reg = SkillRegistry()
    r0 = reg.revision
    reg.upsert(Skill(id="a", name="A", origin="global"))
    assert reg.revision == r0 + 1
    reg.remove("g:a")
    assert reg.revision == r0 + 2


def test_activated_skills_context_injects_guard_and_instructions(monkeypatch):
    from app.core.skills import registry

    monkeypatch.setattr(config.settings, "skill_context_max_bytes", 8192)
    registry.upsert(
        Skill(
            id="resume", name="简历分析", description="分析简历",
            triggers=["简历"], prompt="输出评估。", origin="global",
        )
    )
    ctx = activated_skills_context("帮我分析简历", user_id=1)
    assert "技能说明" in ctx  # 引导语
    assert "简历分析" in ctx
    assert "输出评估。" in ctx
    registry.remove("g:resume")


def test_activated_skills_context_no_match_returns_empty():
    assert activated_skills_context("今天天气如何", user_id=1) == ""


def test_scan_global_and_executable_tools(tmp_path, monkeypatch):
    monkeypatch.setattr(config.settings, "skills_dir", str(tmp_path))
    (tmp_path / "hello-skill").mkdir()
    (tmp_path / "hello-skill" / "SKILL.md").write_text(
        textwrap.dedent(
            """\
            ---
            name: hello-skill
            description: 打招呼
            trigger: [hello]
            ---
            打声招呼。
            """
        ),
        encoding="utf-8",
    )
    (tmp_path / "hello-skill" / "tools.py").write_text(
        textwrap.dedent(
            """\
            from langchain_core.tools import tool
            from app.agent.tools import safe_tool

            @tool
            @safe_tool
            async def hello() -> str:
                \"\"\"say hello\"\"\"
                return "hi"
            """
        ),
        encoding="utf-8",
    )
    skills = scan_global()
    assert len(skills) == 1
    s = skills[0]
    assert s.id == "hello-skill"
    assert [t.name for t in s.executable_tools] == ["hello"]
    # tools.py 加载失败不阻断技能本身
    bad = _load_skill_tools(tmp_path)
    assert bad == []


def test_sync_global_add_and_remove(tmp_path, monkeypatch):
    from app.core.skills import registry

    monkeypatch.setattr(config.settings, "skills_dir", str(tmp_path))
    (tmp_path / "one.md").write_text(
        "---\nname: one\ntrigger: [一]\n---\n一", encoding="utf-8"
    )
    sync_global()
    assert registry.get("g:one") is not None
    # 技能被删除后全量同步会移除
    (tmp_path / "one.md").unlink()
    sync_global()
    assert registry.get("g:one") is None


def test_get_memory_tool(monkeypatch):
    from app.agent import tools as T

    async def fake_get_memory(uid):
        return ["用户是数据分析师"]

    import app.service.memory_service as ms

    monkeypatch.setattr(ms, "get_memory", fake_get_memory)

    async def main():
        tok = T.current_user_id.set(1)
        try:
            return await T.get_memory.ainvoke({})
        finally:
            T.current_user_id.reset(tok)

    assert asyncio.run(main()) == "- 用户是数据分析师"


def test_tool_manager_includes_skill_tools(monkeypatch):
    from app.agent.tool_manager import ToolManager
    from app.agent.tools import get_storage_usage
    from app.core.skills import registry

    registry.upsert(
        Skill(
            id="tooled", name="带工具", origin="global",
            executable_tools=[get_storage_usage],
        )
    )
    try:
        tm = ToolManager()
        names = {t.name for t in tm.all()}
        assert "get_storage_usage" in names
        assert "web_search" in names
        # general worker 工具集同样包含技能可执行工具
        general_names = {t.name for t in tm.for_worker("general")}
        assert "get_storage_usage" in general_names
    finally:
        registry.remove("g:tooled")
