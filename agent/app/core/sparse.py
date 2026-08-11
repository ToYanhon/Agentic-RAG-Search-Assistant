"""中文稀疏检索向量：jieba 分词 + 稳定哈希 token id。

Qdrant 负责 IDF 权重计算，本模块只生成每个 chunk/query 的词频向量。
稳定哈希避免维护跨用户、跨批次的全局词表。
"""

from collections import Counter
import re
import zlib

import jieba
from qdrant_client import models


_TOKEN_RE = re.compile(r"[a-z0-9_\u4e00-\u9fff]+", re.IGNORECASE)


def tokenize(text: str) -> list[str]:
    """把中文、英文和数字拆成可复现的检索 token。"""
    tokens: list[str] = []
    for raw in jieba.lcut_for_search(text or "", HMM=False):
        normalized = raw.strip().lower()
        if not normalized:
            continue
        tokens.extend(_TOKEN_RE.findall(normalized))
    return tokens


def token_id(token: str) -> int:
    """返回跨进程稳定的非负 token id（不使用 Python 内置 hash）。"""
    return zlib.crc32(token.lower().encode("utf-8")) & 0x7FFFFFFF


def build_sparse_vector(text: str) -> models.SparseVector:
    """生成 Qdrant sparse vector，values 使用原始词频。"""
    counts = Counter(token_id(token) for token in tokenize(text))
    indices = sorted(counts)
    return models.SparseVector(
        indices=indices,
        values=[float(counts[index]) for index in indices],
    )
