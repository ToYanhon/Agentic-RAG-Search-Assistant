"""重排器与 embedding.search 组合逻辑测试 — mock cross-encoder 与 Qdrant。"""

import asyncio

from app.core import embedding as emb
from app.core import reranker as rr

MODEL = "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1"


def run(coro):
    return asyncio.run(coro)


def _sig(x):
    if x >= 0:
        import math
        return 1.0 / (1.0 + math.exp(-x))
    import math
    return math.exp(x) / (1.0 + math.exp(x))


async def _noop():
    return None


class _FakeCE:
    def __init__(self, scores):
        self.scores = scores
        self.pairs = []

    def predict(self, pairs):
        self.pairs.extend(pairs)
        return self.scores


def test_rerank_reorders_and_truncates(monkeypatch):
    fake = _FakeCE([-1.0, 3.0, 0.5])
    monkeypatch.setattr(rr.reranker, "_load_model", _noop)
    monkeypatch.setattr(rr.reranker, "_predict_sync", lambda pairs: fake.predict(pairs))

    cands = [
        {"file_id": 1, "chunk": "a", "score": 0.1},
        {"file_id": 2, "chunk": "b", "score": 0.2},
        {"file_id": 3, "chunk": "c", "score": 0.3},
    ]
    out = run(rr.reranker.rerank("q", cands, top_k=2))
    assert [c["file_id"] for c in out] == [2, 3]
    # logits → sigmoid 归一化
    assert abs(out[0]["score"] - _sig(3.0)) < 1e-6
    assert abs(out[1]["score"] - _sig(0.5)) < 1e-6


def test_rerank_empty_inputs():
    assert run(rr.reranker.rerank("q", [], 5)) == []
    assert run(rr.reranker.rerank("q", [{"chunk": "a"}], 0)) == []


def test_search_rerank_threshold_truncate(monkeypatch):
    fake = _FakeCE([3.0, -1.0, -3.0])

    async def fake_embed(texts):
        return [[0.0] * 384]

    monkeypatch.setattr(emb.searcher, "_embed", fake_embed)

    raw = [
        {"file_id": 1, "chunk": "x" * 300, "score": 0.1},
        {"file_id": 2, "chunk": "y" * 300, "score": 0.2},
        {"file_id": 3, "chunk": "z" * 300, "score": 0.3},
    ]
    async def fake_vs_search(qv, uid, top_k):
        return raw

    monkeypatch.setattr(emb.vector_store, "search", fake_vs_search)
    monkeypatch.setattr(rr.reranker, "_load_model", _noop)
    monkeypatch.setattr(rr.reranker, "_predict_sync", lambda pairs: fake.predict(pairs))

    out = run(emb.searcher.search("q", 1, top_k=3))
    # logits [3.0, -1.0, -3.0] → sigmoid [0.95, 0.27, 0.047]；best=0.95，floor=max(0.01, 0.095)=0.095 → 滤掉 file3
    assert [r["file_id"] for r in out] == [1, 2]
    # chunk 截断到 200
    assert all(len(r["chunk"]) == 200 for r in out)
    # 分数覆写为 sigmoid 归一化概率
    assert abs(out[0]["score"] - _sig(3.0)) < 1e-6
    assert abs(out[1]["score"] - _sig(-1.0)) < 1e-6


def test_search_fallback_on_rerank_error(monkeypatch):
    async def fake_embed(texts):
        return [[0.0] * 384]

    monkeypatch.setattr(emb.searcher, "_embed", fake_embed)

    raw = [
        {"file_id": 1, "chunk": "a" * 300, "score": 0.9},
        {"file_id": 2, "chunk": "b" * 300, "score": 0.5},
        {"file_id": 3, "chunk": "c" * 300, "score": 0.1},
    ]

    async def fake_vs_search(qv, uid, top_k):
        return raw

    monkeypatch.setattr(emb.vector_store, "search", fake_vs_search)

    async def boom(query, candidates, top_k):
        raise RuntimeError("model missing")

    monkeypatch.setattr(rr.reranker, "rerank", boom)

    out = run(emb.searcher.search("q", 1, top_k=2))
    # 降级：退回首粗排 top_k，不抛异常
    assert [r["file_id"] for r in out] == [1, 2]
    assert all(len(r["chunk"]) == 200 for r in out)


def test_search_empty_candidates(monkeypatch):
    async def fake_embed(texts):
        return [[0.0] * 384]

    monkeypatch.setattr(emb.searcher, "_embed", fake_embed)

    async def fake_vs_search(qv, uid, top_k):
        return []

    monkeypatch.setattr(emb.vector_store, "search", fake_vs_search)
    assert run(emb.searcher.search("q", 1, top_k=5)) == []


def test_search_absolute_floor_filters_noise(monkeypatch):
    fake = _FakeCE([-6.0, -7.0, -8.0])

    async def fake_embed(texts):
        return [[0.0] * 384]

    monkeypatch.setattr(emb.searcher, "_embed", fake_embed)

    raw = [
        {"file_id": 1, "chunk": "a" * 10, "score": 0.1},
        {"file_id": 2, "chunk": "b" * 10, "score": 0.2},
        {"file_id": 3, "chunk": "c" * 10, "score": 0.3},
    ]

    async def fake_vs_search(qv, uid, top_k):
        return raw

    monkeypatch.setattr(emb.vector_store, "search", fake_vs_search)
    monkeypatch.setattr(rr.reranker, "_load_model", _noop)
    monkeypatch.setattr(rr.reranker, "_predict_sync", lambda pairs: fake.predict(pairs))

    # logits [-6,-7,-8] → sigmoid 全部 < 0.01 绝对下限 → 空（噪声查询被全滤）
    assert run(emb.searcher.search("q", 1, top_k=3)) == []
