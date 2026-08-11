"""轻量可观测性 — 自研 Prometheus 文本格式指标采集（零第三方依赖）。

设计:
  - MetricsRegistry 进程内聚合（线程安全，asyncio 单线程事件循环下用锁保护）
  - Counter: 单调递增计数器，支持 label 维度
  - Histogram: 延迟分布，输出 _bucket/_sum/_count 供 Prometheus 计算分位数
  - render(): 输出 Prometheus text exposition format (0.0.4)

指标一览（LLM 调用 / 工具调用 / HTTP 请求）:
  llm_calls_total            {status=success|error|retry, model}
  llm_latency_seconds        histogram
  llm_tokens_total           {type=prompt|completion, model}
  llm_cost_total_yuan        {model}  — 按官方定价估算成本
  tool_calls_total           {name, status=success|error}
  tool_latency_seconds       histogram {name}
  http_requests_total        {method, status}
  http_latency_seconds       histogram {method}
"""

import time
from collections import defaultdict
from threading import Lock

# DeepSeek 官方定价（元/百万 tokens，deepseek-v4-flash）
PRICE_PROMPT_YUAN_PER_M = 1.0
PRICE_COMPLETION_YUAN_PER_M = 2.0


class _Counter:
    def __init__(self, name: str, help_text: str):
        self.name = name
        self.help = help_text
        self._values: defaultdict[str, float] = defaultdict(float)
        self._lock = Lock()

    def inc(self, value: float = 1.0, labels: dict | None = None) -> None:
        key = _labels_key(labels)
        with self._lock:
            self._values[key] += value

    def render(self, lines: list[str]) -> None:
        lines.append(f"# HELP {self.name} {self.help}")
        lines.append(f"# TYPE {self.name} counter")
        with self._lock:
            for key, val in self._values.items():
                lines.append(f"{self.name}{key} {val:g}")


class _Histogram:
    BUCKETS = (
        0.01,
        0.05,
        0.1,
        0.25,
        0.5,
        1.0,
        2.5,
        5.0,
        10.0,
        30.0,
        60.0,
        float("inf"),
    )

    def __init__(self, name: str, help_text: str):
        self.name = name
        self.help = help_text
        self._labels: dict[str, dict] = {}
        self._counts: defaultdict[str, float] = defaultdict(float)
        self._sums: defaultdict[str, float] = defaultdict(float)
        self._buckets: defaultdict[str, dict[float, float]] = defaultdict(
            lambda: defaultdict(float)
        )
        self._lock = Lock()

    def observe(self, value: float, labels: dict | None = None) -> None:
        labels = labels or {}
        key = _labels_key(labels)
        with self._lock:
            self._labels[key] = labels
            self._counts[key] += 1
            self._sums[key] += value
            for b in self.BUCKETS:
                if value <= b:
                    self._buckets[key][b] += 1

    def render(self, lines: list[str]) -> None:
        base = self.name
        lines.append(f"# HELP {base} {self.help}")
        lines.append(f"# TYPE {base} histogram")
        with self._lock:
            for key, labels in self._labels.items():
                rest = _labels_key(labels)[1:-1] if labels else ""
                for b in self.BUCKETS:
                    le = f"{b:g}" if b != float("inf") else "+Inf"
                    label_part = f",{rest}" if rest else ""
                    lines.append(
                        f'{base}_bucket{{le="{le}"{label_part}}} {self._buckets[key][b]:g}'
                    )
                lines.append(f"{base}_sum{key} {self._sums[key]:g}")
                lines.append(f"{base}_count{key} {self._counts[key]:g}")


def _labels_key(labels: dict | None) -> str:
    """构造 {label="value",...} 前缀，空则返回 ''（无 label 用法）。"""
    if not labels:
        return ""
    parts = ", ".join(f'{k}="{v}"' for k, v in sorted(labels.items()))
    return "{" + parts + "}"


class MetricsRegistry:
    """线程安全的指标注册表，按名称缓存 Counter/Histogram。"""

    def __init__(self):
        self._counters: dict[str, _Counter] = {}
        self._histograms: dict[str, _Histogram] = {}
        self._lock = Lock()

    def reset(self) -> None:
        """原地清空数据（保留已注册对象引用，便于测试）。"""
        with self._lock:
            for c in self._counters.values():
                c._values.clear()
            for h in self._histograms.values():
                h._labels.clear()
                h._counts.clear()
                h._sums.clear()
                h._buckets.clear()

    def counter(self, name: str, help_text: str) -> _Counter:
        with self._lock:
            if name not in self._counters:
                self._counters[name] = _Counter(name, help_text)
            return self._counters[name]

    def histogram(self, name: str, help_text: str) -> _Histogram:
        with self._lock:
            if name not in self._histograms:
                self._histograms[name] = _Histogram(name, help_text)
            return self._histograms[name]

    def render(self) -> str:
        lines: list[str] = []
        for m in self._histograms.values():
            m.render(lines)
        for m in self._counters.values():
            m.render(lines)
        return "\n".join(lines) + "\n"


metrics = MetricsRegistry()

# 预注册常用指标（渲染顺序固定）
llm_calls = metrics.counter("llm_calls_total", "LLM 调用次数（含重试）")
llm_latency = metrics.histogram("llm_latency_seconds", "LLM 调用耗时分布")
llm_tokens = metrics.counter("llm_tokens_total", "LLM token 用量")
llm_cost = metrics.counter("llm_cost_total_yuan", "LLM 估算成本（元）")
tool_calls = metrics.counter("tool_calls_total", "Agent 工具调用次数")
tool_latency = metrics.histogram("tool_latency_seconds", "Agent 工具耗时分布")
http_requests = metrics.counter("http_requests_total", "HTTP 请求数")
http_latency = metrics.histogram("http_latency_seconds", "HTTP 请求耗时分布")
skill_activations = metrics.counter("skill_activations_total", "技能激活次数（按技能与来源）")
route_decisions = metrics.counter("route_decisions_total", "Supervisor 路由决策（按目标 worker）")


def now() -> float:
    return time.perf_counter()
