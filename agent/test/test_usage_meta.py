"""usage 缓存感知计费测试 — prompt_tokens_details.cached_tokens 拆分计价。"""

import pytest

from app.agent import llm_utils as lu


@pytest.fixture(autouse=True)
def reset_metrics(monkeypatch):
    from app.core.metrics import metrics

    metrics.reset()
    yield


def _cached(um):
    return lu._cached_tokens(um)


def test_cached_tokens_from_prompt_tokens_details():
    um = {"input_tokens": 100, "output_tokens": 10, "prompt_tokens_details": {"cached_tokens": 80}}
    assert _cached(um) == 80


def test_cached_tokens_fallback_flat_field():
    um = {"input_tokens": 100, "output_tokens": 10, "prompt_cache_hit_tokens": 60}
    assert _cached(um) == 60


def test_cached_tokens_absent():
    assert _cached({"input_tokens": 100, "output_tokens": 10}) == 0


def test_record_usage_meta_splits_cost(monkeypatch):
    from app.core.model_meta import ModelMeta

    monkeypatch.setattr(lu, "model_meta", lambda p, m: ModelMeta(128000, 10.0, 20.0, 1.0))
    um = {"input_tokens": 100, "output_tokens": 10, "prompt_tokens_details": {"cached_tokens": 80}}
    lu._record_usage_meta(um, "deepseek-v4-flash")

    from app.core.metrics import llm_cost, llm_tokens

    # 成本 = uncached(20)/1e6*10 + cached(80)/1e6*1 + out(10)/1e6*20
    assert abs(llm_cost._values["{model=\"deepseek-v4-flash\"}"] - (200 + 80 + 200) / 1e6) < 1e-9
    assert llm_tokens._values['{model="deepseek-v4-flash", type="prompt_cached"}'] == 80
    assert llm_tokens._values['{model="deepseek-v4-flash", type="prompt"}'] == 100
