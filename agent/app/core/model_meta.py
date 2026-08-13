"""模型元数据表 —— 各服务商模型的上下文窗口与定价（用于 token 花费估算与上下文展示）。

匹配顺序：provider+模型精确 → provider+模型前缀 → 全局前缀（provider="*"）→ 全局默认。
未知模型不抛错，回退默认 context 窗口与 env 单价，保证展示不中断。
价格单位为「元 / 百万 tokens」，按官方公开定价维护（2026-08）。
"""

from dataclasses import dataclass

from app.config import settings
from app.core.llm import normalize_provider


@dataclass(frozen=True)
class ModelMeta:
    context_window: int
    price_in: float  # 元/百万 tokens（未命中缓存）
    price_out: float
    price_in_cached: float = 0.0  # 命中缓存价；0 表示与 price_in 相同


DEFAULT_CONTEXT = 128_000

# 缓存命中默认折扣（无官方价时的兜底，约 1/10）
CACHE_HIT_RATIO = 0.1

# 键: (provider, 模型名或前缀)。provider 用前端 LLMProvider（openai/anthropic/kimi/zhipu）。
_OPENAI = "openai"
_ANTHROPIC = "anthropic"
_KIMI = "kimi"
_ZHIPU = "zhipu"

_TABLE: dict[tuple[str, str], ModelMeta] = {
    # DeepSeek（OpenAI 兼容，context caching 自动开启，命中约 1/10）
    (_OPENAI, "deepseek-v4-flash"): ModelMeta(128_000, 1.0, 2.0, 0.1),
    # OpenAI（自动缓存，命中约 1/10）
    (_OPENAI, "gpt-5.3"): ModelMeta(128_000, 18.75, 75.0, 1.875),
    # Anthropic（Claude 原生，prompt caching 命中约 1/10）
    (_ANTHROPIC, "claude-3-5-sonnet"): ModelMeta(200_000, 22.5, 112.5, 2.25),
    (_ANTHROPIC, "claude-3-7-sonnet"): ModelMeta(200_000, 22.5, 112.5, 2.25),
    (_ANTHROPIC, "claude-3-5-haiku"): ModelMeta(200_000, 1.5, 7.5, 0.15),
    # Kimi / Moonshot（OpenAI 兼容，缓存约 1/10）
    (_KIMI, "kimi-k3"): ModelMeta(128_000, 4.0, 16.0, 0.4),
    # 智谱 GLM（OpenAI 兼容，缓存约 1/10）
    (_ZHIPU, "glm-4-flash"): ModelMeta(128_000, 0.4, 0.4, 0.04),
    (_ZHIPU, "glm-4-flash-250414"): ModelMeta(128_000, 0.4, 0.4, 0.04),
    (_ZHIPU, "glm-4-plus"): ModelMeta(128_000, 20.0, 20.0, 2.0),
    (_ZHIPU, "glm-4-air"): ModelMeta(128_000, 0.5, 1.0, 0.05),
    (_ZHIPU, "glm-4-long"): ModelMeta(1_000_000, 0.5, 2.0, 0.05),
    (_ZHIPU, "glm-4v"): ModelMeta(128_000, 20.0, 20.0, 2.0),
}

# provider 级兜底（模型未命中时用该服务商最常用模型的价格）
_PROVIDER_FALLBACK: dict[str, ModelMeta] = {
    _ANTHROPIC: ModelMeta(200_000, 22.5, 112.5, 2.25),
    _KIMI: ModelMeta(128_000, 4.0, 16.0, 0.4),
    _ZHIPU: ModelMeta(128_000, 0.4, 0.4, 0.04),
}

_PROVIDERS = {_OPENAI, _ANTHROPIC, _KIMI, _ZHIPU}


def _default_meta() -> ModelMeta:
    """全局默认：context 128K，价格回落 config 的 env 单价，缓存价按 1/10 折扣。"""
    return ModelMeta(
        DEFAULT_CONTEXT,
        settings.llm_price_input,
        settings.llm_price_output,
        settings.llm_price_input * CACHE_HIT_RATIO,
    )


def model_meta(provider: str | None, model: str | None) -> ModelMeta:
    """按 (provider, model) 查元数据；未知组合回退 provider 级兜底 → 全局默认。"""
    p = provider or settings.llm_provider
    if p not in _PROVIDERS:
        p = normalize_provider(p)
    m = (model or "").strip().lower()

    exact = _TABLE.get((p, m))
    if exact:
        return exact
    for (pk, prefix), meta in _TABLE.items():
        if pk == p and prefix and m.startswith(prefix):
            return meta
    return _PROVIDER_FALLBACK.get(p, _default_meta())
