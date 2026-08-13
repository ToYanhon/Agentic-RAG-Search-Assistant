"""AgentWorkflow 完整回合测试 — supervisor 路由 → general worker 工具循环 → 收尾。

全部 LLM 调用用脚本化假 LLM（astream 产出 AIMessageChunk）；session_service /
记忆 / 技能 / 上下文均 mock，不触任何基础设施。
"""

import asyncio
from types import SimpleNamespace

from langchain_core.messages import AIMessageChunk

from app.agent.tool_manager import ToolManager
from app.agent.workflow import AgentWorkflow


class _ScriptedLLM:
    """按调用次序返回预设 chunk 的假 LLM。

    outputs: [{"content": str, "tool_call_chunks": [...], "usage_metadata": {...}}, ...]
    """

    def __init__(self, outputs):
        self._outputs = list(outputs)
        self.calls = 0

    async def astream(self, messages):
        out = self._outputs[min(self.calls, len(self._outputs) - 1)]
        self.calls += 1
        yield AIMessageChunk(
            content=out.get("content", ""),
            tool_call_chunks=out.get("tool_call_chunks", []),
            usage_metadata=out.get("usage_metadata"),
        )


def _tool_call(name, tid):
    return [{"name": name, "args": "{}", "id": tid, "index": 0}]


_USAGE = {"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}


class _FakeMemory:
    def __init__(self):
        self.scheduled = []

    async def load(self, uid):
        return []

    def schedule_extraction(self, uid, conversation, session_id):
        self.scheduled.append((uid, conversation, session_id))


class _FakeSkills:
    async def activate(self, uid, message):
        return ""


class _FakeContext:
    async def build(self, history, message, session_id):
        return SimpleNamespace(
            messages=[{"role": "user", "content": message}],
            summary_usage=None,
            to_meta=lambda: {},
        )


class _FakeSession:
    def __init__(self):
        self.persisted = None
        self.usage = None

    async def get_messages(self, session_id):
        return []

    async def replace_messages(self, session_id, dicts):
        self.persisted = dicts

    async def add_usage(self, session_id, inp, out, cost, model):
        self.usage = (inp, out, cost, model)


def _patch_session(monkeypatch, session):
    """把 workflow 用到的 session_service 方法替换为假实现。"""
    import app.service.session_service as svc_mod

    monkeypatch.setattr(svc_mod, "get_messages", session.get_messages)
    monkeypatch.setattr(svc_mod, "replace_messages", session.replace_messages)
    monkeypatch.setattr(svc_mod, "add_usage", session.add_usage)


def _make_workflow(session, memory=None):
    return AgentWorkflow(
        tool_manager=ToolManager(),
        memory_manager=memory or _FakeMemory(),
        skill_manager=_FakeSkills(),
        context_manager=_FakeContext(),
    ), session


def test_workflow_full_turn(monkeypatch):
    import app.service.memory_service as ms

    session = _FakeSession()
    memory = _FakeMemory()
    _patch_session(monkeypatch, session)
    fake = _ScriptedLLM(
        [
            {"tool_call_chunks": _tool_call("transfer_to_general", "c1"), "usage_metadata": _USAGE},
            {"tool_call_chunks": _tool_call("get_memory", "c2"), "usage_metadata": _USAGE},
            {"content": "记忆已完成", "usage_metadata": _USAGE},
            {"content": "好的，已处理", "usage_metadata": _USAGE},
        ]
    )
    monkeypatch.setattr("app.agent.workflow.build_llm", lambda **kw: fake)

    async def fake_get_memory(uid):
        return ["偏好英文"]

    monkeypatch.setattr(ms, "get_memory", fake_get_memory)

    wf, session = _make_workflow(session, memory)

    async def run():
        return [ev async for ev in wf.turn(1, "s1", "你好")]

    events = asyncio.run(run())

    types = [e["type"] for e in events]
    assert types[0] == "route"
    assert types[-2] == "meta"
    assert types[-1] == "done"
    assert "tool_start" in types and "tool_end" in types

    route = events[types.index("route")]
    assert route["to"] == "general"

    texts = [e["content"] for e in events if e["type"] == "text"]
    assert "记忆已完成" in texts
    # supervisor 收尾已抑制（worker 已给出文本答复，避免重复复述）
    assert "好的，已处理" not in texts

    # 持久化：最后一条 AI 消息 + usage（worker 的最终答复）
    assert session.persisted is not None
    assert session.persisted[-1]["role"] == "ai"
    assert session.persisted[-1]["content"] == "记忆已完成"
    assert "usage" in session.persisted[-1]
    assert session.usage is not None

    # 后台记忆提炼已调度
    assert memory.scheduled and memory.scheduled[0][0] == 1

    # LLM 共调用 4 次（含被抑制的 supervisor 收尾）
    assert fake.calls == 4


def test_workflow_direct_answer_no_route(monkeypatch):
    """纯闲聊：supervisor 直接答复，无路由、无工具。"""
    session = _FakeSession()
    _patch_session(monkeypatch, session)
    fake = _ScriptedLLM([{"content": "你好呀"}])
    monkeypatch.setattr("app.agent.workflow.build_llm", lambda **kw: fake)

    wf, session = _make_workflow(session)

    async def run():
        return [ev async for ev in wf.turn(1, "s1", "你好")]

    events = asyncio.run(run())
    types = [e["type"] for e in events]
    assert "route" not in types
    assert "tool_start" not in types
    texts = [e["content"] for e in events if e["type"] == "text"]
    assert "你好呀" in texts
    assert session.persisted[-1]["content"] == "你好呀"
    assert fake.calls == 1
