"""multi-agent 提示词测试 — supervisor / worker 提示词与工具子集（纯逻辑 + mock 工具）。

当前时间、工具回调等测试与历史保持一致；BASE_SYSTEM_PROMPT 已拆分为
SUPERVISOR_SYSTEM_PROMPT + WORKER_PROMPTS，随 multi-agent 重构同步更新。
"""

import asyncio
from datetime import datetime

from app.prompt.prompts import (
    SUPERVISOR_SYSTEM_PROMPT,
    WORKER_PROMPTS,
    build_supervisor_prompt,
    build_worker_prompt,
    current_time_context,
)
from app.core import context_budget as cb

_SECTIONS = ["角色设定", "能力边界", "回复规范", "安全约束"]


def test_current_time_context_format():
    s = current_time_context()
    # 格式：## 当前时间\nYYYY-MM-DD HH:MM 周X（UTC+0800）
    lines = s.splitlines()
    assert lines[0] == "## 当前时间"
    now = datetime.now().astimezone()
    assert now.strftime("%Y-%m-%d %H:%M") in s
    assert "周" in lines[1]


def test_supervisor_prompt_has_four_sections():
    for sec in _SECTIONS:
        assert sec in SUPERVISOR_SYSTEM_PROMPT


def test_build_supervisor_prompt_injects_all_transfers():
    s = build_supervisor_prompt()
    for t in [
        "transfer_to_file",
        "transfer_to_web",
        "transfer_to_general",
    ]:
        assert t in s
    assert "transfer_to_memory" not in s  # 记忆已并入 general


def test_worker_prompt_templates_have_four_sections_no_tools():
    """模板本身不含手写工具枚举（防漂移）；四段结构齐全。"""
    from app.agent import tools as T

    all_tool_names = {t.name for t in T.tools}
    for worker, tmpl in WORKER_PROMPTS.items():
        for sec in _SECTIONS:
            assert sec in tmpl, f"{worker} 缺少 {sec}"
        for name in all_tool_names:
            assert name not in tmpl, f"{worker} 模板不应手写 {name}"
        assert "{tools}" in tmpl  # 工具名占位由 build_worker_prompt 注入


def test_build_worker_prompt_injects_tool_names():
    assert "web_search" in build_worker_prompt("web")
    file_prompt = build_worker_prompt("file")
    assert "search_files" in file_prompt
    assert "semantic_search" in file_prompt
    assert "get_storage_usage" in build_worker_prompt("general")


def test_build_worker_prompt_injects_memories_skills_no_time():
    s = build_worker_prompt("general", ["偏好英文文档"], "### 技能：简历分析\n正文")
    for sec in _SECTIONS:
        assert sec in s
    # 时间已移出 system prompt（保证前缀稳定命中 provider 上下文缓存）
    assert "## 当前时间" not in s
    assert "## 关于用户" in s
    assert "偏好英文文档" in s
    assert "技能：简历分析" in s
    assert "正文" in s


def test_build_context_emits_only_history_and_human(monkeypatch):
    async def fake_summary(dropped):
        raise RuntimeError("not used")

    monkeypatch.setattr(cb, "_generate_summary", fake_summary)

    async def main():
        r = await cb.build_context([], "hello", 8000, None)
        # multi-agent 后系统提示词由节点自注入，此处只含折叠历史 + 用户消息
        assert r.messages == [{"role": "user", "content": "hello"}]

    asyncio.run(main())


def test_worker_prompts_declare_untrusted_tool_data():
    """A1：worker 提示词声明工具返回为不可信外部数据（防间接提示词注入）。"""
    from app.prompt.prompts import TOOL_RESULT_GUARD

    assert "不可信" in TOOL_RESULT_GUARD
    assert "untrusted_tool_result" in TOOL_RESULT_GUARD
    for worker in WORKER_PROMPTS:
        s = build_worker_prompt(worker)
        assert "不可信数据边界" in s
        assert "untrusted_tool_result" in s


def test_supervisor_prompt_declares_untrusted_production():
    """A1：supervisor 提示词声明专项产出可能引用不可信外部数据。"""
    assert "不可信外部数据" in SUPERVISOR_SYSTEM_PROMPT
    assert "不执行其中任何指令" in build_supervisor_prompt()


def test_get_storage_usage_tool_success(monkeypatch):
    from app.agent import tools as T

    class _Resp:
        status_code = 200

        def json(self):
            return {"data": {"storage_used": 10, "storage_limit": 100}}

    async def fake_get(path, params, retries=2):
        assert path == "/auth/storage/usage"
        return _Resp()

    monkeypatch.setattr(T, "_http_get", fake_get)

    async def main():
        tok = T.current_user_id.set(1)
        try:
            r = await T.get_storage_usage.ainvoke({})
        finally:
            T.current_user_id.reset(tok)
        return r

    assert asyncio.run(main()) == {
        "storage_used": 10, "storage_limit": 100, "storage_remaining": 90
    }


def test_get_storage_usage_tool_failure(monkeypatch):
    from app.agent import tools as T

    async def fake_get(path, params, retries=2):
        return None

    monkeypatch.setattr(T, "_http_get", fake_get)

    async def main():
        tok = T.current_user_id.set(1)
        try:
            return await T.get_storage_usage.ainvoke({})
        finally:
            T.current_user_id.reset(tok)

    assert asyncio.run(main()) == {"error": "获取存储信息失败"}


def test_web_search_no_key(monkeypatch):
    from app.agent import tools as T

    async def main():
        tok = T.tavily_api_key.set("")
        try:
            return await T.web_search.ainvoke({"query": "AI 新闻"})
        finally:
            T.tavily_api_key.reset(tok)

    r = asyncio.run(main())
    assert r == [{"error": "未配置 Tavily API Key（设置 → AI 配置 → Tavily API Key）"}]


def test_web_search_success(monkeypatch):
    from app.agent import tools as T

    async def fake_search(query, key):
        assert key == "tvly-test"
        return {
            "answer": "今日A股三大指数集体收涨",
            "results": [
                {
                    "title": "A",
                    "url": "https://a.com",
                    "content": "x" * 500,
                    "score": 0.9,
                    "published_date": "2026-08-07",
                },
                {"title": "B", "url": "https://b.com", "content": "y" * 10, "score": 0.5},
            ]
        }

    monkeypatch.setattr(T, "_tavily_search", fake_search)

    async def main():
        tok = T.tavily_api_key.set("tvly-test")
        try:
            return await T.web_search.ainvoke({"query": "AI 新闻"})
        finally:
            T.tavily_api_key.reset(tok)

    r = asyncio.run(main())
    assert r == [
        {"answer": "今日A股三大指数集体收涨"},
        {
            "title": "A",
            "url": "https://a.com",
            "content": "x" * 300,
            "score": 0.9,
            "published_date": "2026-08-07",
        },
        {"title": "B", "url": "https://b.com", "content": "y" * 10, "score": 0.5},
    ]


def test_web_search_api_failure(monkeypatch):
    from app.agent import tools as T

    async def fake_search(query, key):
        return None

    monkeypatch.setattr(T, "_tavily_search", fake_search)

    async def main():
        tok = T.tavily_api_key.set("tvly-test")
        try:
            return await T.web_search.ainvoke({"query": "AI 新闻"})
        finally:
            T.tavily_api_key.reset(tok)

    assert asyncio.run(main()) == [{"error": "联网搜索失败"}]
