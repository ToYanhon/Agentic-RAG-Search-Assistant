"""LLM 客户端工厂 —— 按 provider（类型）构建 LangChain Runnable。

- openai：OpenAI 兼容接口（深算/OpenAI/通义/智谱/Kimi(Moonshot) 等，base_url 决定具体服务商）
- anthropic：Claude 原生 Anthropic Messages API（/v1/messages，原生 tool_use）

对话（tools 绑定）与 RAG 摘要（无工具）共用此工厂，一处实现支持全部类型。
"""

import logging

from langchain_core.runnables import Runnable
from langchain_openai import ChatOpenAI
from pydantic import SecretStr

from app.config import settings
from app.core.llm_override import get_llm_override

logger = logging.getLogger(__name__)

PROVIDER_DEFAULT = "openai"
PROVIDER_ANTHROPIC = "anthropic"


def current_model() -> str:
    """当前请求实际使用的模型（override 优先，env 默认兜底）。"""
    ov = get_llm_override()
    return (ov.model if ov and ov.model else None) or settings.llm_model


def current_provider() -> str:
    """当前请求实际使用的 provider（override 优先，env 默认兜底）。"""
    ov = get_llm_override()
    return (ov.provider if ov and ov.provider else None) or settings.llm_provider


def normalize_provider(provider: str | None) -> str:
    """将任意输入归一为受支持 provider；未知值回退 openai（OpenAI 兼容最通用）。"""
    p = (provider or "").strip().lower()
    if p in ("anthropic", "claude"):
        return PROVIDER_ANTHROPIC
    return PROVIDER_DEFAULT


def build_llm(
    provider: str,
    model: str,
    api_key: str,
    base_url: str,
    *,
    temperature: float | None = None,
    timeout: int = 60,
    max_retries: int = 0,
    tools: list | None = None,
) -> Runnable:
    """构建 LLM Runnable。tools 非空时绑定工具（对话环节）；摘要/兜底不绑。

    temperature 默认不传（None）→ 不随请求发送，用供应商默认值（部分网关只允许 1）。
    显式传入则覆盖；未显式传入时回落到 settings.llm_temperature（LLM_TEMPERATURE env）。

    anthropic 走 ChatAnthropic（Messages API，bind_tools → 原生 tool_use 块）；
    其余（含 kimi/Moonshot）走 ChatOpenAI（OpenAI 兼容格式）。
    """
    if temperature is None:
        temperature = settings.llm_temperature
    if normalize_provider(provider) == PROVIDER_ANTHROPIC:
        try:
            from langchain_anthropic import ChatAnthropic
        except ImportError:  # pragma: no cover - 依赖未装时回退 OpenAI 兼容
            logger.error("langchain-anthropic 未安装，anthropic provider 回退 openai 兼容")
            _chat = _chat_openai(model, api_key, base_url, temperature, timeout, max_retries)
        else:
            # anthropic 路径会无条件把 temperature 放进请求体，None 时显式用其默认值 1.0
            anthropic_temp = 1.0 if temperature is None else temperature
            _chat = ChatAnthropic(
                model_name=model,
                stop=None,
                api_key=SecretStr(api_key) if api_key else SecretStr(""),
                base_url=base_url or None,
                temperature=anthropic_temp,
                timeout=timeout,
                max_retries=max_retries,
            )
    else:
        _chat = _chat_openai(model, api_key, base_url, temperature, timeout, max_retries)
    return _chat.bind_tools(tools) if tools else _chat


def _chat_openai(
    model: str,
    api_key: str,
    base_url: str,
    temperature: float | None,
    timeout: int,
    max_retries: int,
) -> ChatOpenAI:
    return ChatOpenAI(
        model=model,
        api_key=SecretStr(api_key) if api_key else None,
        base_url=base_url,
        temperature=temperature,
        timeout=timeout,
        max_retries=max_retries,
    )