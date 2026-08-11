"""上下文预算与摘要折叠 — 纯逻辑测试（LLM 摘要 mock）。"""

import asyncio
from unittest.mock import patch

import pytest

from app.core import context_budget as cb


def run(coro):
    return asyncio.run(coro)


def _mk(rows: list) -> list[dict]:
    h = []
    for rid, role, content in rows:
        h.append({"role": role, "content": content, "rowid": rid})
    return h


def test_estimate_tokens():
    assert cb.estimate_tokens("你好世界" * 10) == 40 + 4
    assert cb.estimate_tokens("hello world" * 10) == 110 // 4 + 4


def test_budget_small_window():
    assert cb._budget_tokens(8000) == 4800
    assert cb._budget_tokens(128000) == 128000 - cb.OUTPUT_RESERVE_BIG


def test_greedy_prefers_latest():
    h = _mk([(i * 2 + 1, "user", f"问{i}" * 50) for i in range(15)])
    r = run(cb.build_context(h, "now", 1500, None))
    kept_rows = sorted(m["rowid"] for m in r.messages if "rowid" in m)
    # 最新消息必须保留，早期被折叠
    assert kept_rows[-1] == 29
    assert kept_rows[0] > 1
    assert r.truncated
    assert r.dropped > 0


def test_tool_chain_atomic():
    h = _mk([
        (1, "user", "问" * 30),
        (2, "ai", "答" * 30),
        (3, "user", "查" * 300),
        (4, "ai", "",),  # tool_calls
        (5, "tool", "工具全文" * 300),
        (6, "ai", "总结" * 300),
    ])
    h[3]["tool_calls"] = [{"name": "read_file_content"}]
    r = run(cb.build_context(h, "新提问", 6000, None))
    kept = {int(m["rowid"]) for m in r.messages if "rowid" in m}
    # 工具链（4+5 必须同存或同弃），且最新 AI 总结(6)优先保留
    assert (4 in kept) == (5 in kept)
    assert 6 in kept


def test_summary_generated_and_injected():
    h = _mk([(i * 2 + 1, "user", "大" * 120) for i in range(10)])

    async def fake_gen(prev_summary, msgs):
        return ("测试摘要", {"input_tokens": 5, "output_tokens": 3})

    with patch.object(cb, "_generate_summary", fake_gen):
        r = run(cb.build_context(h, "问", 500, None))
    assert r.truncated and r.dropped > 0
    assert r.summary_used and r.summary_generated
    sys_msgs = [m for m in r.messages if m["role"] == "system"]
    assert any("对话摘要" in str(m["content"]) for m in sys_msgs)
    assert any("测试摘要" in str(m["content"]) for m in sys_msgs)


def test_summary_cached_reuse():
    h = _mk([(i * 2 + 1, "user", "大" * 120) for i in range(10)])
    with patch.object(cb, "_generate_summary") as gen, \
         patch("app.service.session_service.get_session_summary",
               lambda sid: run_future(("旧摘要", 999))):
        r = run(cb.build_context(h, "问", 500, "s1"))
    gen.assert_not_called()
    assert r.summary_used
    assert not r.summary_generated
    assert "旧摘要" in r.summary_text


def test_summary_cumulative_chains_previous():
    """新一轮折叠时，以上一轮累积摘要为输入重写生成（滚动累积）。"""
    h = _mk([(i * 2 + 1, "user", "大" * 120) for i in range(10)])

    captured = {}

    async def fake_gen(prev_summary, msgs):
        captured["prev"] = prev_summary
        captured["n"] = len(msgs)
        return ("累积新摘要", {"input_tokens": 5, "output_tokens": 3})

    with patch.object(cb, "_generate_summary", fake_gen), \
         patch("app.service.session_service.get_session_summary",
               lambda sid: run_future(("上一轮摘要", 5))):  # 覆盖点低于本轮 fold_max
        r = run(cb.build_context(h, "问", 500, "s1"))
    assert r.summary_generated
    assert captured["prev"] == "上一轮摘要"  # 上一轮摘要被喂给生成
    assert captured["n"] > 0
    assert "累积新摘要" in r.summary_text


def test_generate_summary_failure_falls_back(monkeypatch):
    h = _mk([(i * 2 + 1, "user", "大" * 120) for i in range(10)])

    async def boom(prev_summary, msgs):
        raise Exception("boom")

    monkeypatch.setattr(cb, "_generate_summary", boom)
    r = run(cb.build_context(h, "问", 500, None))
    assert r.truncated
    assert not r.summary_used
    assert not r.summary_generated


def run_future(value):
    future = asyncio.get_event_loop().create_future()
    future.set_result(value)
    return future