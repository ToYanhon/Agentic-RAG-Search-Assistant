"""Qdrant 向量存储 — 语义索引的持久化层（替代原 Redis kb:*）。

- 单 collection `kb` + `user_id` payload 过滤（多用户隔离在库内）
- `dense` 稠密向量 + `sparse` 稀疏向量，分别用于语义和关键词召回
- 磁盘持久化，无 TTL；稠密向量使用 HNSW ANN 检索
- payload 预留 metadata 字段（后续层级 section_path / type 可扩展）
"""

import logging

from qdrant_client import AsyncQdrantClient, models

from app.config import settings

logger = logging.getLogger(__name__)

COLLECTION = "kb"
EMBEDDING_SIZE = 384  # MiniLM 384 维
DENSE_VECTOR = "dense"
SPARSE_VECTOR = "sparse"
UPSERT_BATCH = 128
_POINT_SEQ_SCALE = 1_000_000  # point id = file_id * SCALE + seq


class VectorStore:
    def __init__(self) -> None:
        self._client: AsyncQdrantClient | None = None
        self._collection_ready = False

    @property
    def client(self) -> AsyncQdrantClient:
        if self._client is None:
            self._client = AsyncQdrantClient(url=settings.qdrant_url, timeout=10)
        return self._client

    async def close(self) -> None:
        if self._client is not None:
            try:
                await self._client.close()
            except Exception:
                logger.exception("failed to close qdrant client")
            self._client = None
            self._collection_ready = False

    async def ensure_collection(self) -> None:
        """幂等建双向量 collection（不存在才建）。"""
        if self._collection_ready:
            return
        try:
            exists = await self.client.collection_exists(COLLECTION)
        except Exception:
            logger.exception("qdrant collection_exists failed")
            return
        if exists:
            if not hasattr(self.client, "get_collection"):
                return
            info = await self.client.get_collection(COLLECTION)
            params = getattr(getattr(info, "config", None), "params", None)
            vectors = getattr(params, "vectors", None)
            sparse_vectors = getattr(params, "sparse_vectors", None)
            if isinstance(vectors, dict) and DENSE_VECTOR in vectors and isinstance(sparse_vectors, dict) and SPARSE_VECTOR in sparse_vectors:
                self._collection_ready = True
                return
            raise RuntimeError(
                f"Qdrant collection {COLLECTION!r} uses the old schema; "
                "recreate it before indexing"
            )

        await self.client.create_collection(
            COLLECTION,
            vectors_config={
                DENSE_VECTOR: models.VectorParams(
                    size=EMBEDDING_SIZE, distance=models.Distance.COSINE
                )
            },
            sparse_vectors_config={
                SPARSE_VECTOR: models.SparseVectorParams(modifier=models.Modifier.IDF)
            },
        )
        self._collection_ready = True
        logger.info("qdrant collection %s created with dense/sparse vectors", COLLECTION)

    async def recreate_collection(self) -> None:
        """删除并重建检索 collection；仅用于开发环境 schema 迁移。"""
        try:
            self._collection_ready = False
            if await self.client.collection_exists(COLLECTION):
                await self.client.delete_collection(COLLECTION)
        except Exception:
            logger.exception("qdrant collection recreation failed")
            raise
        await self.ensure_collection()

    async def upsert_chunks(
        self,
        file_id: int,
        user_id: int,
        chunks: list[str],
        embeddings: list[list[float]],
        chunk_type: str = "text",
        sparse_vectors: list[models.SparseVector] | None = None,
    ) -> None:
        """写入一个文件的全部 chunk（先删旧再写，幂等覆盖）。

        chunk_type: 语义族标注（rag.detect_kind 产物，如 text/pdf/docx/image），
        仅作 payload 元数据与检索结果展示，不参与向量匹配。
        """
        await self.ensure_collection()
        await self.delete_file(file_id, user_id)
        if not chunks:
            return
        if sparse_vectors is not None and len(sparse_vectors) != len(chunks):
            raise ValueError("sparse vector count must match chunk count")
        points = [
            models.PointStruct(
                id=file_id * _POINT_SEQ_SCALE + seq,
                vector={
                    DENSE_VECTOR: embeddings[seq],
                    **(
                        {SPARSE_VECTOR: sparse_vectors[seq]}
                        if sparse_vectors is not None
                        else {}
                    ),
                },
                payload={
                    "user_id": user_id,
                    "file_id": file_id,
                    "chunk": chunks[seq],
                    "seq": seq,
                    "type": chunk_type,
                },
            )
            for seq in range(len(chunks))
        ]
        for i in range(0, len(points), UPSERT_BATCH):
            await self.client.upsert(COLLECTION, points=points[i : i + UPSERT_BATCH])

    async def delete_file(self, file_id: int, user_id: int) -> None:
        """删除一个文件的所有索引点。"""
        try:
            await self.client.delete(
                COLLECTION,
                points_selector=models.FilterSelector(
                    filter=models.Filter(
                        must=[
                            models.FieldCondition(
                                key="user_id", match=models.MatchValue(value=user_id)
                            ),
                            models.FieldCondition(
                                key="file_id", match=models.MatchValue(value=file_id)
                            ),
                        ]
                    )
                ),
            )
        except Exception:
            logger.exception("qdrant delete failed for file %s", file_id)

    async def delete_user(self, user_id: int) -> None:
        """删除一个用户的全部索引。"""
        try:
            await self.client.delete(
                COLLECTION,
                points_selector=models.FilterSelector(
                    filter=models.Filter(
                        must=[
                            models.FieldCondition(
                                key="user_id", match=models.MatchValue(value=user_id)
                            )
                        ]
                    )
                ),
            )
        except Exception:
            logger.exception("qdrant delete_user failed for %s", user_id)

    async def search(
        self, query_vec: list[float], user_id: int, top_k: int = 5
    ) -> list[dict]:
        """按稠密向量检索该用户最近的 chunk。"""
        try:
            await self.ensure_collection()
            res = await self.client.query_points(
                COLLECTION,
                query=query_vec,
                using=DENSE_VECTOR,
                query_filter=models.Filter(
                    must=[
                        models.FieldCondition(
                            key="user_id", match=models.MatchValue(value=user_id)
                        )
                    ]
                ),
                limit=top_k,
                with_payload=True,
            )
        except Exception:
            logger.exception("qdrant search failed")
            return []
        return self._results(res)

    async def search_sparse(
        self, query_vec: models.SparseVector, user_id: int, top_k: int = 5
    ) -> list[dict]:
        """按稀疏向量检索该用户最近的 chunk。"""
        try:
            await self.ensure_collection()
            res = await self.client.query_points(
                COLLECTION,
                query=query_vec,
                using=SPARSE_VECTOR,
                query_filter=models.Filter(
                    must=[
                        models.FieldCondition(
                            key="user_id", match=models.MatchValue(value=user_id)
                        )
                    ]
                ),
                limit=top_k,
                with_payload=True,
            )
        except Exception:
            logger.exception("qdrant sparse search failed")
            return []
        return self._results(res)

    @staticmethod
    def _results(res) -> list[dict]:
        out = []
        for p in res.points:
            payload = p.payload or {}
            out.append(
                {
                    "file_id": payload.get("file_id"),
                    "chunk": payload.get("chunk", ""),
                    "score": float(p.score),
                    "seq": payload.get("seq"),
                    "type": payload.get("type", "text"),
                }
            )
        return out

    async def file_indexed(self, file_id: int, user_id: int) -> bool:
        """判断文件是否已建立索引。"""
        try:
            await self.ensure_collection()
            res = await self.client.count(
                COLLECTION,
                count_filter=models.Filter(
                    must=[
                        models.FieldCondition(
                            key="user_id", match=models.MatchValue(value=user_id)
                        ),
                        models.FieldCondition(
                            key="file_id", match=models.MatchValue(value=file_id)
                        ),
                    ]
                ),
            )
            return res.count > 0
        except Exception:
            logger.exception("qdrant count failed for %s", file_id)
            return False


vector_store = VectorStore()
