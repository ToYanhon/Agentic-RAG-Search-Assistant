"""AgentWorkflow — 自研 Agent 主流程（不依赖 langgraph）：单 async generator 走完整回合。

回合生命周期（原散落在 chat.py / graph.py 的逻辑收拢于此）：
  历史加载 → 记忆加载 → 技能激活 → 上下文折叠
  → supervisor 路由（transfer 工具，schema 描述含 WORKER_TAGLINES）
  → 选中的 worker ReAct 循环（llm.astream 流式文本 + ToolManager 执行工具）
  → 回 supervisor 收尾 → 消息持久化 → 后台记忆提炼 → meta/done 事件

产出的 SSE 事件类型与旧实现一致：text / tool_start / tool_end / route / meta / error / done，
前端无需改动。
"""

import logging
import re
import time
import uuid
from collections.abc import AsyncIterator
from typing import Any

from langchain_core.messages import BaseMessage, SystemMessage, ToolMessage
from langchain_core.tools import StructuredTool

from app.agent.context_manager import ContextManager
from app.agent.llm_utils import llm_stream, record_stream_usage
from app.agent.memory_manager import MemoryManager
from app.agent.message import dict_to_message, message_to_dict
from app.agent.skill_manager import SkillManager
from app.agent.tool_manager import ToolManager
from app.agent.workers import WORKER_NAMES, WORKER_TAGLINES
from app.config import settings
from app.core.llm import build_llm, current_model, current_provider
from app.core.llm_override import get_llm_override
from app.core.metrics import route_decisions
from app.core.model_meta import model_meta
from app.prompt.prompts import build_supervisor_prompt, build_worker_prompt

logger = logging.getLogger(__name__)

MAX_TOOL_ROUNDS = 6  # 单个 worker 内最大工具循环次数（防失控）

# 疑似 API Key（sk-…）正则，错误摘要里剥掉防泄露
_KEY_RE = re.compile(r"sk-[A-Za-z0-9_\-]{6,}")


def _sanitize_error(e: Exception) -> str:
    """把异常转成安全、简短、可自排障的摘要（不透传完整请求体/密钥）。"""
    msg = str(e) or type(e).__name__
    msg = _KEY_RE.sub("sk-***", msg)
    msg = re.sub(r"\s+", " ", msg).strip()
    if len(msg) > 200:
        msg = msg[:200] + "…"
    return f"{type(e).__name__}: {msg}"


def _transfer_tool(target: str) -> StructuredTool:
    """构造把任务转移给指定 worker 的工具（无参，直接返回目标名）。

    描述携带该 worker 的能力摘要，Supervisor 依据工具 schema 做路由决策，
    提示词不再重复枚举各助手职责。
    """
    display = WORKER_NAMES[target]
    tagline = WORKER_TAGLINES[target]

    async def _run() -> str:
        return target

    return StructuredTool.from_function(
        name=f"transfer_to_{target}",
        description=f"把当前任务转移给{display}处理（{tagline}）。调用后专项助手将接管后续工作。",
        coroutine=_run,
    )


TRANSFER_TOOLS: list[StructuredTool] = [_transfer_tool(t) for t in WORKER_NAMES]


class AgentWorkflow:
    def __init__(
        self,
        tool_manager: ToolManager,
        memory_manager: MemoryManager,
        skill_manager: SkillManager,
        context_manager: ContextManager,
    ) -> None:
        self.tools = tool_manager
        self.memory = memory_manager
        self.skills = skill_manager
        self.context = context_manager

    @staticmethod
    def _llm(tools: list | None = None):
        """按请求级覆盖构建绑定了指定工具的 LLM（supervisor/worker 各自独立实例）。"""
        ov = get_llm_override()
        return build_llm(
            provider=(ov.provider if ov and ov.provider else None)
            or settings.llm_provider,
            model=(ov.model if ov and ov.model else None) or settings.llm_model,
            api_key=(ov.api_key if ov and ov.api_key else None) or settings.llm_api_key,
            base_url=(ov.base_url if ov and ov.base_url else None)
            or settings.llm_base_url,
            timeout=60,
            tools=tools,
        )

    async def turn(
        self, user_id: int, session_id: str, message: str
    ) -> AsyncIterator[dict[str, Any]]:
        """执行完整回合，产出 SSE 事件序列。"""
        from app.service import session_service as svc

        started = time.perf_counter()
        usage: dict[str, int] = {"in": 0, "out": 0}
        messages: list[BaseMessage] = []
        budget = None
        model = current_model()
        provider = current_provider()
        mm = model_meta(provider, model)

        # 1. 前置：历史 / 记忆 / 技能 / 上下文折叠
        try:
            history = await svc.get_messages(session_id)
            memories = await self.memory.load(user_id)
            skills_context = await self.skills.activate(user_id, message)
            budget = await self.context.build(history, message, session_id)
            messages = [dict_to_message(d) for d in budget.messages]
            if budget.summary_usage:
                record_stream_usage(budget.summary_usage, model)
                usage["in"] += int(budget.summary_usage.get("input_tokens") or 0)
                usage["out"] += int(budget.summary_usage.get("output_tokens") or 0)
        except Exception as e:
            logger.exception("agent turn setup failed")
            yield {"type": "error", "content": _sanitize_error(e)}
            return

        # 2. supervisor 路由 + worker 循环（流式产出事件）
        try:
            async for ev in self._supervisor(messages, usage, memories, skills_context):
                yield ev
        except Exception as e:
            logger.exception("agent turn run failed")
            yield {"type": "error", "content": _sanitize_error(e)}
        finally:
            # 3. 持久化
            dicts: list[dict] = []
            try:
                persistable = [m for m in messages if not isinstance(m, SystemMessage)]
                dicts = [message_to_dict(m) for m in persistable]
                if (
                    dicts
                    and dicts[-1].get("role") == "ai"
                    and (usage["in"] or usage["out"])
                ):
                    dicts[-1]["usage"] = {
                        "input_tokens": usage["in"],
                        "output_tokens": usage["out"],
                        "total_tokens": usage["in"] + usage["out"],
                        "model": model,
                        "provider": provider,
                        "context_window": mm.context_window,
                        "cost_yuan": round(
                            usage["in"] / 1e6 * mm.price_in
                            + usage["out"] / 1e6 * mm.price_out,
                            6,
                        ),
                        "latency_ms": int((time.perf_counter() - started) * 1000),
                    }
                await svc.replace_messages(session_id, dicts)
            except Exception:
                logger.exception("failed to persist messages")

            # 4. 后台记忆提炼（不阻塞 SSE 完成）
            try:
                ai_last = next(
                    (
                        d.get("content", "")
                        for d in reversed(dicts)
                        if d.get("role") == "ai"
                    ),
                    "",
                )
                conversation = f"用户：{message}\n助手：{str(ai_last)[:1500]}"
                if conversation.strip():
                    self.memory.schedule_extraction(user_id, conversation, session_id)
            except Exception:
                logger.exception("failed to schedule memory extraction")

            # 5. meta + done
            cost = usage["in"] / 1e6 * mm.price_in + usage["out"] / 1e6 * mm.price_out
            yield {
                "type": "meta",
                "model": model,
                "provider": provider,
                "context_window": mm.context_window,
                "input_tokens": usage["in"],
                "output_tokens": usage["out"],
                "total_tokens": usage["in"] + usage["out"],
                "cost_yuan": round(cost, 6),
                "latency_ms": int((time.perf_counter() - started) * 1000),
                **(budget.to_meta() if budget else {}),
            }
            try:
                if usage["in"] or usage["out"]:
                    await svc.add_usage(
                        session_id, usage["in"], usage["out"], cost, model
                    )
            except Exception:
                logger.exception("failed to accumulate session usage")
            yield {"type": "done"}

    # ---------- supervisor 路由 ----------

    async def _supervisor(
        self,
        messages: list[BaseMessage],
        usage: dict[str, int],
        memories: list[str],
        skills_context: str,
    ) -> AsyncIterator[dict[str, Any]]:
        sup = self._llm(TRANSFER_TOOLS)
        while True:
            out: list = []
            async for text in self._stream_llm(
                sup,
                [SystemMessage(content=build_supervisor_prompt())] + messages,
                usage,
                out,
            ):
                if text:
                    yield {"type": "text", "content": text}
            ai = out[0]
            messages.append(ai)
            calls = getattr(ai, "tool_calls", None) or []
            if not calls:
                return  # 最终答复已流式输出
            name = str(calls[0].get("name", ""))
            if not name.startswith("transfer_to_"):
                return
            target = name[len("transfer_to_") :]
            if target not in WORKER_NAMES:
                return
            # 转移工具执行结果落一条 ToolMessage（与旧 langgraph transfer 节点语义一致）
            tid = calls[0].get("id") or uuid.uuid4().hex
            messages.append(
                ToolMessage(content=target, tool_call_id=tid, id=uuid.uuid4().hex)
            )
            route_decisions.inc(1, {"to": target})
            yield {"type": "route", "to": target}
            async for ev in self._worker(
                target, messages, usage, memories, skills_context
            ):
                yield ev

    # ---------- worker ReAct 循环 ----------

    async def _worker(
        self,
        worker: str,
        messages: list[BaseMessage],
        usage: dict[str, int],
        memories: list[str],
        skills_context: str,
    ) -> AsyncIterator[dict[str, Any]]:
        tools = self.tools.for_worker(worker)
        llm = self._llm(tools)
        sys_prompt = build_worker_prompt(worker, memories, skills_context)

        for _ in range(MAX_TOOL_ROUNDS):
            out: list = []
            async for text in self._stream_llm(
                llm, [SystemMessage(content=sys_prompt)] + messages, usage, out
            ):
                if text:
                    yield {"type": "text", "content": text}
            ai = out[0]
            messages.append(ai)
            calls = getattr(ai, "tool_calls", None) or []
            if not calls:
                return  # worker 完成
            for tc in calls:
                tname = str(tc.get("name", ""))
                targs = tc.get("args", {}) or {}
                tid = tc.get("id") or uuid.uuid4().hex
                yield {"type": "tool_start", "name": tname}
                result = await self.tools.execute(tname, targs)
                yield {"type": "tool_end", "name": tname, "result": str(result)[:300]}
                messages.append(
                    ToolMessage(
                        content=str(result), tool_call_id=tid, id=uuid.uuid4().hex
                    )
                )

    # ---------- 流式 LLM 调用 ----------

    async def _stream_llm(
        self,
        llm,
        messages: list[BaseMessage],
        usage: dict[str, int],
        out: list,
    ) -> AsyncIterator[str]:
        """流式调用 LLM：逐个 yield 文本块；完成后把最终 AIMessage 放入 out 并记录 usage。"""
        async for text, final in llm_stream(llm, messages):
            if text:
                yield text
        out.append(final)
        um = getattr(final, "usage_metadata", None)
        if um:
            usage["in"] += int(um.get("input_tokens") or 0)
            usage["out"] += int(um.get("output_tokens") or 0)
            record_stream_usage(um, current_model())


_workflow: AgentWorkflow | None = None


def get_workflow() -> AgentWorkflow:
    """返回全局唯一 AgentWorkflow（懒组装各 Manager，供路由层复用）。"""
    global _workflow
    if _workflow is None:
        _workflow = AgentWorkflow(
            tool_manager=ToolManager(),
            memory_manager=MemoryManager(),
            skill_manager=SkillManager(),
            context_manager=ContextManager(),
        )
    return _workflow
