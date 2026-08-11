"""LLM 请求级覆盖 —— 从前端 AI 设置（X-LLM-* 头）透传的 contextvar。

workflow.py（主对话 LLM）与 rag.py（RAG 摘要 LLM）共用同一机制，
保证同一请求内所有 LLM 调用都走用户配置的 Provider / Base URL / API Key / 模型。
请求结束必须 reset，避免污染其他请求/用户。
"""

import contextvars
from dataclasses import dataclass

# provider 取值：openai（OpenAI 兼容，含 deepseek/kimi 等）、anthropic（Claude 原生）
PROVIDER_DEFAULT = "openai"
PROVIDER_ANTHROPIC = "anthropic"


@dataclass
class LLMOverride:
    """按请求覆盖 LLM 配置；字段为空则沿用 env 默认。"""

    provider: str | None = None
    base_url: str | None = None
    api_key: str | None = None
    model: str | None = None


_llm_override: contextvars.ContextVar[LLMOverride | None] = contextvars.ContextVar(
    "llm_override", default=None
)


def set_llm_override(ov: LLMOverride | None):
    return _llm_override.set(ov)


def reset_llm_override(token) -> None:
    _llm_override.reset(token)


def get_llm_override() -> LLMOverride | None:
    return _llm_override.get()