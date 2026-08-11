"""语义搜索引擎 — 本地 sentence-transformers embedding，索引持久化到 Qdrant。

模型: paraphrase-multilingual-MiniLM-L12-v2（多语言，支持中文，384 维）
本地运行，无需外部 embedding API。

存储: Qdrant collection `kb`（core/vector_store.py）——磁盘持久化、
稠密/稀疏双向量、user_id payload 过滤（替代原 Redis kb:*）。

检索: 稠密/稀疏召回 → RRF 融合 → cross-encoder 精排
（core/reranker.py）→ 阈值门控 → top_k。
"""

import asyncio
import logging

from sentence_transformers import SentenceTransformer

from app.config import settings
from app.core.reranker import reranker
from app.core.sparse import build_sparse_vector
from app.core.vector_store import vector_store

logger = logging.getLogger(__name__)

MODEL_NAME = "paraphrase-multilingual-MiniLM-L12-v2"


class SemanticSearch:
    def __init__(self):
        self._model: SentenceTransformer | None = None
        self._model_lock = asyncio.Lock()

    async def _load_model(self) -> SentenceTransformer:
        if self._model is None:
            async with self._model_lock:
                if self._model is None:
                    self._model = SentenceTransformer(MODEL_NAME)
        return self._model

    def _embed_sync(self, texts: list[str]) -> list[list[float]]:
        assert self._model is not None
        return self._model.encode(texts, normalize_embeddings=True).tolist()

    async def _embed(self, texts: list[str]) -> list[list[float]]:
        await self._load_model()
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(None, self._embed_sync, texts)

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        """对外公开的文本向量化入口（记忆去重等复用，归一化后余弦相似度即点积）。"""
        if not texts:
            return []
        return await self._embed(texts)

    async def index_file(
        self, file_id: int, user_id: int, chunks: list[str], chunk_type: str = "text"
    ):
        embeddings = await self._embed(chunks)
        sparse_vectors = [build_sparse_vector(chunk) for chunk in chunks]
        await vector_store.upsert_chunks(
            file_id,
            user_id,
            chunks,
            embeddings,
            chunk_type,
            sparse_vectors=sparse_vectors,
        )

    async def delete_file(self, file_id: int, user_id: int):
        await vector_store.delete_file(file_id, user_id)

    async def retrieve_candidates(
        self,
        query: str,
        user_id: int,
        top_k: int = 5,
        hybrid: bool | None = None,
        limit: int | None = None,
    ) -> list[dict]:
        """返回重排前候选，供线上检索和离线评估复用。"""
        emb = await self._embed([query])
        candidates = max(settings.rerank_candidates, top_k)
        if hybrid is None:
            hybrid = settings.hybrid_search
        if hybrid:
            dense, sparse = await asyncio.gather(
                vector_store.search(emb[0], user_id, candidates),
                vector_store.search_sparse(
                    build_sparse_vector(query),
                    user_id,
                    max(settings.sparse_top_k, candidates),
                ),
            )
            raw = _rrf([dense, sparse], settings.rrf_k, candidates)
        else:
            dense = await vector_store.search(emb[0], user_id, candidates)
            raw = dense
        return raw[: limit or candidates]

    async def search(self, query: str, user_id: int, top_k: int = 5) -> list[dict]:
        raw = await self.retrieve_candidates(query, user_id, top_k=top_k)
        return await self.rank_candidates(query, raw, top_k)

    async def rank_candidates(
        self, query: str, candidates: list[dict], top_k: int = 5
    ) -> list[dict]:
        """对已召回候选执行精排和低相关门控。"""
        raw = candidates
        if not raw:
            return []
        try:
            ranked = await reranker.rerank(query, raw, top_k)
            best = ranked[0]["score"] if ranked else 0.0
            floor = max(settings.rerank_min_score, best * settings.rerank_min_ratio)
            kept = [r for r in ranked if r["score"] >= floor]
        except Exception:
            logger.exception("rerank failed, fallback to ANN top_k")
            kept = raw[:top_k]
        for r in kept:
            r["chunk"] = (r.get("chunk") or "")[:200]
        return kept


searcher = SemanticSearch()


def _rrf(result_lists: list[list[dict]], rrf_k: int, limit: int) -> list[dict]:
    """融合多路排序结果，保留同一 chunk 的最高质量元数据。"""
    merged: dict[tuple[object, object, str], dict] = {}
    for results in result_lists:
        for rank, result in enumerate(results, start=1):
            key = (result.get("file_id"), result.get("seq"), result.get("chunk", ""))
            item = merged.setdefault(key, dict(result))
            item["score"] = item.get("_rrf_score", 0.0) + 1.0 / (rrf_k + rank)
            item["_rrf_score"] = item["score"]
    ranked = sorted(merged.values(), key=lambda item: item["score"], reverse=True)
    for item in ranked:
        item.pop("_rrf_score", None)
    return ranked[:limit]
