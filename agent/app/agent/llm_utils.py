"""LLM 调用工具 — 重试 + usage/成本指标 + 流式分块（自建 Agent 框架，不依赖 langgraph）。

原 graph.py 中的重试/计费逻辑移植于此；流式文本由调用方消费并转 SSE 事件。
"""

import asyncio
import logging
from collections.abc import AsyncIterator
from typing import Any

import httpx
from langchain_core.messages import AIMessage, BaseMessage

from app.core.llm import current_model, current_provider
from app.core.metrics import llm_calls, llm_cost, llm_latency, llm_tokens, now
from app.core.model_meta import model_meta

logger = logging.getLogger(__name__)

LLM_RETRIES = 3
RETRYABLE_STATUS = {408, 429, 500, 502, 503, 504}


def is_retryable(exc: Exception) -> bool:
    """判断异常是否值得重试：超时 / 连接错误 / 429 限流 / 5xx。"""
    if isinstance(exc, httpx.HTTPError):
        return True
    try:
        import openai

        if isinstance(
            exc,
            (openai.APITimeoutError, openai.APIConnectionError, openai.RateLimitError),
        ):
            return True
    except ImportError:
        pass
    try:
        import anthropic

        if isinstance(
            exc,
            (
                anthropic.APITimeoutError,
                anthropic.APIConnectionError,
                anthropic.RateLimitError,
            ),
        ):
            return True
    except ImportError:
        pass
    status = getattr(exc, "status_code", None)
    return status in RETRYABLE_STATUS


def _cached_tokens(um: dict) -> int:
    """从 usage_metadata 提取命中的前缀缓存 token。

    OpenAI 兼容（DeepSeek/OpenAI/智谱等）：usage.prompt_tokens_details.cached_tokens；
    Anthropic 原生：usage.cache_read_input_tokens（经 langchain 归一化后可能在 prompt_tokens_details）。
    """
    ptd = um.get("prompt_tokens_details") or {}
    cached = int(ptd.get("cached_tokens") or 0)
    if not cached:
        cached = int(um.get("prompt_cache_hit_tokens") or 0)
    return max(0, cached)


def _record_usage_meta(um: dict | None, model: str) -> None:
    """按 usage_metadata 记录 token 数与成本（元，按模型真实单价，缓存命中分价）。"""
    if not um:
        return
    prompt = int(um.get("input_tokens") or 0)
    completion = int(um.get("output_tokens") or 0)
    if not prompt and not completion:
        return
    cached = _cached_tokens(um)
    uncached = max(0, prompt - cached)
    labels = {"model": model}
    llm_tokens.inc(prompt, {**labels, "type": "prompt"})
    llm_tokens.inc(cached, {**labels, "type": "prompt_cached"})
    llm_tokens.inc(completion, {**labels, "type": "completion"})
    mm = model_meta(current_provider(), model)
    cached_price = mm.price_in_cached if mm.price_in_cached > 0 else mm.price_in
    cost = (
        uncached / 1e6 * mm.price_in
        + cached / 1e6 * cached_price
        + completion / 1e6 * mm.price_out
    )
    llm_cost.inc(cost, labels)


def record_stream_usage(um: dict | None, model: str) -> None:
    """上报单轮 LLM 调用的 usage（流式路径与摘要折叠路径共用）。"""
    _record_usage_meta(um, model)


def record_result_usage(result, model: str) -> None:
    """从调用结果提取 usage 并计费（非流式节点路径）。"""
    um = getattr(result, "usage_metadata", None) or {}
    if not um:
        usage = (getattr(result, "response_metadata", None) or {}).get(
            "token_usage", {}
        )
        um = {
            "input_tokens": usage.get("prompt_tokens"),
            "output_tokens": usage.get("completion_tokens"),
        }
    _record_usage_meta(um, model)


async def ainvoke_with_retry(llm, messages) -> BaseMessage:
    """非流式调用（带重试 + usage 记录）。用于 supervisor 路由等不流式路径。"""
    labels = {"model": current_model()}
    last_exc: Exception | None = None
    start = now()
    for attempt in range(LLM_RETRIES):
        try:
            result = await llm.ainvoke(messages)
            llm_latency.observe(now() - start, labels)
            llm_calls.inc(1, {**labels, "status": "success"})
            record_result_usage(result, labels["model"])
            return result
        except Exception as e:
            last_exc = e
            llm_calls.inc(1, {**labels, "status": "error"})
            if not is_retryable(e) or attempt == LLM_RETRIES - 1:
                raise
            llm_calls.inc(1, {**labels, "status": "retry"})
            backoff = min(2**attempt, 8)
            logger.warning(
                "LLM 调用失败(%s)，%.1fs 后重试 %d/%d: %s",
                type(e).__name__,  # type: ignore
                backoff,
                attempt + 1,
                LLM_RETRIES - 1,
                e,
            )
            await asyncio.sleep(backoff)
    assert last_exc is not None
    raise last_exc


async def llm_stream(llm, messages) -> AsyncIterator[tuple[str, AIMessage]]:
    """流式调用 LLM：逐个产出 (文本块, None)；完成后产出 ("", 最终 AIMessage)。

    最终消息带 tool_calls / usage_metadata（流式聚合），可直接追加进会话消息。
    记录 llm_calls / llm_latency（流式主路径打点，IMPROVEMENTS.md A11）。
    """
    from langchain_core.messages import AIMessageChunk

    labels = {"model": current_model()}
    start = now()
    chunks: list[AIMessageChunk] = []
    content = ""
    try:
        async for chunk in llm.astream(messages):
            c = getattr(chunk, "content", "") or ""
            if isinstance(c, str):
                content += c
                if c:
                    yield c, None
            chunks.append(chunk)

        merged: AIMessage | AIMessageChunk | None = None
        for c in chunks:
            merged = c if merged is None else merged + c
        if merged is None:
            merged = AIMessage(content=content)

        um = getattr(merged, "usage_metadata", None) or {}
        if not um and chunks:  # 聚合可能丢 usage，回退取最后一个 chunk
            um = getattr(chunks[-1], "usage_metadata", None) or {}
        if getattr(merged, "id", None) is None:
            import uuid

            merged.id = uuid.uuid4().hex

        if isinstance(merged, AIMessageChunk):
            merged = AIMessage(
                content=content,
                tool_calls=[dict(tc) for tc in (getattr(merged, "tool_calls", None) or [])],
                usage_metadata=um or None,
                id=merged.id,
            )
        llm_calls.inc(1, {**labels, "status": "success"})
        yield "", merged
    except Exception:
        llm_calls.inc(1, {**labels, "status": "error"})
        raise
    finally:
        llm_latency.observe(now() - start, labels)
