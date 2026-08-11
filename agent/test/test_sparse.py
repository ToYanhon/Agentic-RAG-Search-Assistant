"""稀疏向量构造测试。"""

from app.core.sparse import build_sparse_vector, token_id, tokenize


def test_tokenize_keeps_chinese_and_ascii_terms():
    tokens = tokenize("Qdrant 向量检索 384 维")

    assert "qdrant" in tokens
    assert "向量" in tokens
    assert "384" in tokens


def test_token_id_is_stable_and_vector_is_sorted():
    vector = build_sparse_vector("混合检索 混合检索 RRF")

    assert token_id("RRF") == token_id("rrf")
    assert vector.indices == sorted(vector.indices)
    assert len(vector.indices) == len(vector.values)
    assert max(vector.values) >= 2.0
