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

from langchain_core.messages import AIMessage, BaseMessage, SystemMessage, ToolMessage
from langchain_core.tools import StructuredTool

from app.agent.context_manager import ContextManager
from app.agent.llm_utils import llm_stream, record_stream_usage
from app.agent.memory_manager import MemoryManager
from app.agent.message import dict_to_message, message_to_dict
from app.agent.skill_manager import SkillManager
from app.agent.tool_manager import ToolManager
from app.agent.workers import WORKER_NAMES, WORKER_TAGLINES
from app.config import settings
from app.core.bg_tasks import await_with_retry
from app.core.llm import build_llm, current_model, current_provider
from app.core.llm_override import get_llm_override
from app.core.metrics import route_decisions
from app.core.model_meta import model_meta
from app.prompt.prompts import build_supervisor_prompt, build_worker_prompt

logger = logging.getLogger(__name__)

MAX_TOOL_ROUNDS = 6  # 单个 worker 内最大工具循环次数（防失控）
# supervisor 最大 worker 转移/协作次数：LLM 反复 transfer ping-pong 时截断，
# 防止每次转移都触发完整 ReAct 循环导致成本/延迟失控（IMPROVEMENTS.md A4）
MAX_SUPERVISOR_ROUNDS = 3

# 疑似 API Key（sk-…）正则，错误摘要里剥掉防泄露
_KEY_RE = re.compile(r"sk-[A-Za-z0-9_\-]{6,}")

# 工具结果不可信边界（防间接提示词注入，IMPROVEMENTS.md A1）：
# 工具返回（文件/网页/检索片段）属不可信外部数据，包进定界符并声明，
# 内容中出现的结束标记会被转义，防止注入方伪造边界。
_UNTRUSTED_START = "«untrusted_tool_result»"
_UNTRUSTED_END = "«/untrusted_tool_result»"
_UNTRUSTED_END_ESCAPED = "«/untrusted_tool_result_escaped»"


def _wrap_tool_result(content: str) -> str:
    """把工具结果包进不可信数据边界；内容中出现的结束标记一律转义。"""
    safe = content.replace(_UNTRUSTED_END, _UNTRUSTED_END_ESCAPED)
    return f"{_UNTRUSTED_START}\n{safe}\n{_UNTRUSTED_END}"


def _sanitize_error(e: Exception) -> str:
    """把异常转成安全、简短、可自排障的摘要（不透传完整请求体/密钥）。"""
    msg = str(e) or type(e).__name__
    msg = _KEY_RE.sub("sk-***", msg)
    msg = re.sub(r"\s+", " ", msg).strip()
    if len(msg) > 200:
        msg = msg[:200] + "…"
    return f"{type(e).__name__}: {msg}"


def _cached_usage_tokens(um: dict) -> int:
    """提取单次 LLM 调用命中的前缀缓存 token（供 meta 汇总展示）。"""
    from app.agent.llm_utils import _cached_tokens

    return _cached_tokens(um)


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
        usage: dict[str, int] = {"in": 0, "out": 0, "cached": 0}
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
            # 当前时间附加到本轮 user 消息末尾（不进 system/历史），
            # 保持 system + 历史前缀稳定以命中 provider 上下文缓存。
            if messages and getattr(messages[-1], "content", None):
                last = messages[-1]
                from app.prompt.prompts import current_time_context

                last.content = f"{last.content}\n\n{current_time_context()}"
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
            # 3. 持久化（走统一骨架重试，最终失败仅日志；不因失败中断 SSE 收尾）
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
                await await_with_retry(
                    lambda: svc.replace_messages(session_id, dicts),
                    name="persist_messages",
                )
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
                "prompt_cache_hit_tokens": usage.get("cached", 0),
                "prompt_cache_miss_tokens": max(0, usage["in"] - usage.get("cached", 0)),
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
        # worker 完成且已输出文本答复时，抑制 supervisor 收尾复述（避免重复内容）
        worker_spoke = False
        transfers = 0
        while True:
            # 达到最大协作轮次：不再转移，直接收尾（成本上限）
            if transfers >= MAX_SUPERVISOR_ROUNDS:
                if worker_spoke:
                    return  # 最近 worker 的文本即最终答复（复述不入历史）
                # 最近 worker 无文本：supervisor 不带转移工具强制给最终答复
                wrap = self._llm([])
                wrap_out: list = []
                async for text in self._stream_llm(
                    wrap,
                    [SystemMessage(content=build_supervisor_prompt())] + messages,
                    usage,
                    wrap_out,
                ):
                    if text:
                        yield {"type": "text", "content": text}
                final = wrap_out[0]
                if not (final.content and isinstance(final.content, str)):
                    # 防御：模型仍无文本（如只回工具调用）→ 兜底文案
                    final = AIMessage(
                        content="已达到本轮协作上限，未能继续处理；请重新表述您的需求。"
                    )
                    yield {"type": "text", "content": final.content}
                messages.append(final)
                return
            out: list = []
            if worker_spoke:
                # 仅取 supervisor 的 tool_calls 判断是否需再次转移，不流式复述文本
                await self._collect(llm=sup, messages=[
                    SystemMessage(content=build_supervisor_prompt())] + messages,
                    usage=usage, out=out)
            else:
                async for text in self._stream_llm(
                    sup,
                    [SystemMessage(content=build_supervisor_prompt())] + messages,
                    usage,
                    out,
                ):
                    if text:
                        yield {"type": "text", "content": text}
            ai = out[0]
            calls = getattr(ai, "tool_calls", None) or []
            if not calls:
                # worker 已给出最终答复且 supervisor 无再转移需求：收尾复述文本不入历史
                if worker_spoke:
                    return
                messages.append(ai)
                return  # 最终答复已流式输出
            messages.append(ai)
            name = str(calls[0].get("name", ""))
            if not name.startswith("transfer_to_"):
                return
            target = name[len("transfer_to_") :]
            if target not in WORKER_NAMES:
                return
            worker_spoke = False
            transfers += 1
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
                if ev["type"] == "text" and ev.get("content"):
                    worker_spoke = True
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
                        content=_wrap_tool_result(str(result)),
                        tool_call_id=tid,
                        id=uuid.uuid4().hex,
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
            usage["cached"] += _cached_usage_tokens(um)
            record_stream_usage(um, current_model())

    async def _collect(
        self,
        llm,
        messages: list[BaseMessage],
        usage: dict[str, int],
        out: list,
    ) -> None:
        """流式调用 LLM 但不产出文本（用于 supervisor 收尾去重）：仅取最终消息与 usage。"""
        async for _text, final in llm_stream(llm, messages):
            pass
        out.append(final)
        um = getattr(final, "usage_metadata", None)
        if um:
            usage["in"] += int(um.get("input_tokens") or 0)
            usage["out"] += int(um.get("output_tokens") or 0)
            usage["cached"] += _cached_usage_tokens(um)
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
