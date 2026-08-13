"""可观测性测试——指标采集与 Prometheus 文本格式渲染。"""

import pytest
from app.agent.tools import safe_tool
from app.core import metrics as m


@pytest.fixture(autouse=True)
def reset_registry():
    m.metrics.reset()
    yield


def test_counter_inc_and_labels():
    c = m.metrics.counter("t_calls", "test")
    c.inc(1, {"a": "1"})
    c.inc(1, {"a": "1"})
    c.inc(2, {"a": "2"})
    text = m.metrics.render()
    assert 't_calls{a="1"} 2' in text
    assert 't_calls{a="2"} 2' in text


def test_histogram_buckets_and_sum_count():
    h = m.metrics.histogram("t_lat", "test")
    h.observe(0.05)
    h.observe(0.8)
    text = m.metrics.render()
    assert "# TYPE t_lat histogram" in text
    assert 't_lat_bucket{le="0.01"} 0' in text
    assert 't_lat_bucket{le="0.1"} 1' in text
    assert 't_lat_bucket{le="1"} 2' in text
    assert 't_lat_bucket{le="+Inf"} 2' in text
    assert "t_lat_sum 0.85" in text
    assert "t_lat_count 2" in text


def test_histogram_with_labels():
    h = m.metrics.histogram("t_lat2", "test")
    h.observe(0.3, {"method": "GET"})
    text = m.metrics.render()
    assert 't_lat2_bucket{le="0.5",method="GET"} 1' in text
    assert 't_lat2_sum{method="GET"} 0.3' in text


@pytest.mark.asyncio
async def test_safe_tool_records_metrics():
    class T:
        @safe_tool
        async def ok(self):
            return "fine"

        @safe_tool
        async def boom(self):
            raise ValueError("x")

    t = T()
    await t.ok()
    await t.boom()
    text = m.metrics.render()
    assert 'tool_calls_total{name="ok", status="success"} 1' in text
    assert 'tool_calls_total{name="boom", status="error"} 1' in text
    assert "tool_latency_seconds" in text


def test_llm_cost_estimation():
    cost = (
        1000 / 1e6 * m.PRICE_PROMPT_YUAN_PER_M
        + 500 / 1e6 * m.PRICE_COMPLETION_YUAN_PER_M
    )
    assert cost == 0.002  # 1000 输入 + 500 输出 ≈ 0.002 元


class FakeMsg:
    def __init__(self, usage_metadata=None, response_metadata=None):
        self.usage_metadata = usage_metadata
        self.response_metadata = response_metadata or {}


def test_record_usage_prefers_usage_metadata(monkeypatch):
    from app.agent import llm_utils as graph
    from app.core import metrics as m2

    calls = {}

    def fake_inc(v, labels):
        calls[labels["type"]] = v

    monkeypatch.setattr(graph.llm_tokens, "inc", fake_inc)
    monkeypatch.setattr(graph.llm_cost, "inc", lambda *a, **k: None)

    msg = FakeMsg(usage_metadata={"input_tokens": 1000, "output_tokens": 500})
    graph.record_result_usage(msg, "x")
    assert calls == {"prompt": 1000, "prompt_cached": 0, "completion": 500}


def test_record_usage_fallback_to_token_usage(monkeypatch):
    from app.agent import llm_utils as graph

    calls = {}

    def fake_inc(v, labels):
        calls[labels["type"]] = v

    monkeypatch.setattr(graph.llm_tokens, "inc", fake_inc)
    monkeypatch.setattr(graph.llm_cost, "inc", lambda *a, **k: None)

    msg = FakeMsg(
        usage_metadata=None,
        response_metadata={
            "token_usage": {"prompt_tokens": 300, "completion_tokens": 60}
        },
    )
    graph.record_result_usage(msg, "x")
    assert calls == {"prompt": 300, "prompt_cached": 0, "completion": 60}
