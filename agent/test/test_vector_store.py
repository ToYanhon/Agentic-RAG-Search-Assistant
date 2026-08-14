"""Qdrant vector store 封装测试 — mock AsyncQdrantClient 验证逻辑。"""

import asyncio

from app.core import vector_store as vs

store = vs.vector_store


def run(coro):
    return asyncio.run(coro)


class _FakeClient:
    """记录调用的假 Qdrant 客户端。"""

    def __init__(self):
        self.exists = False
        self.created = False
        self.deleted = []
        self.upserted = []
        self.points = []

    async def collection_exists(self, name):
        return self.exists

    async def create_collection(self, name, vectors_config=None, sparse_vectors_config=None):
        self.created = True
        self.exists = True

    async def upsert(self, collection, points=None):
        self.upserted.extend(points or [])

    async def delete(self, collection, points_selector=None):
        self.deleted.append(points_selector)

    async def count(self, collection, count_filter=None):
        return type("C", (), {"count": len(self.points)})()

    async def query_points(
        self,
        collection,
        query=None,
        using=None,
        query_filter=None,
        limit=None,
        with_payload=None,
    ):
        return type("R", (), {"points": self.points})()

    async def close(self):
        self.closed = True


def _fresh(monkeypatch, client):
    monkeypatch.setattr(vs, "AsyncQdrantClient", lambda url=None, timeout=None: client)
    monkeypatch.setattr(store, "_client", None)
    monkeypatch.setattr(store, "_collection_ready", False)


def test_ensure_collection_creates(monkeypatch):
    client = _FakeClient()
    _fresh(monkeypatch, client)

    async def main():
        await store.ensure_collection()
        assert client.created is True
        assert vs.COLLECTION == "kb"

    run(main())


def test_upsert_chunks_uses_compound_id(monkeypatch):
    client = _FakeClient()
    _fresh(monkeypatch, client)

    async def main():
        await store.upsert_chunks(7, 1, ["a", "b", "c"], [[1.0] * 384] * 3)
        ids = [p.id for p in client.upserted]
        assert ids == [7 * 1_000_000, 7 * 1_000_000 + 1, 7 * 1_000_000 + 2]
        assert client.upserted[0].payload["user_id"] == 1
        assert client.upserted[0].payload["file_id"] == 7
        assert client.upserted[2].payload["chunk"] == "c"
        assert client.upserted[0].vector[vs.DENSE_VECTOR] == [1.0] * 384

    run(main())


def test_file_indexed_true(monkeypatch):
    client = _FakeClient()
    client.points = [object()]
    _fresh(monkeypatch, client)

    async def main():
        assert await store.file_indexed(7, 1) is True

    run(main())


def test_file_indexed_false(monkeypatch):
    client = _FakeClient()
    _fresh(monkeypatch, client)

    async def main():
        assert await store.file_indexed(7, 1) is False

    run(main())


def test_search_returns_payload(monkeypatch):
    client = _FakeClient()
    client.points = [type("P", (), {"payload": {"file_id": 7, "chunk": "hi", "seq": 1, "type": "pdf"}, "score": 0.9})()]
    _fresh(monkeypatch, client)

    async def main():
        out = await store.search([1.0] * 384, 1, 5)
        assert out == [{"file_id": 7, "chunk": "hi", "score": 0.9, "seq": 1, "type": "pdf"}]

    run(main())


def test_search_returns_type_default(monkeypatch):
    client = _FakeClient()
    client.points = [type("P", (), {"payload": {"file_id": 7, "chunk": "hi", "seq": 1}, "score": 0.9})()]
    _fresh(monkeypatch, client)

    async def main():
        out = await store.search([1.0] * 384, 1, 5)
        assert out[0]["type"] == "text"

    run(main())


def test_sparse_search_returns_payload(monkeypatch):
    client = _FakeClient()
    client.points = [type("P", (), {"payload": {"file_id": 7, "chunk": "hi", "seq": 1}, "score": 0.8})()]
    _fresh(monkeypatch, client)

    async def main():
        out = await store.search_sparse(vs.models.SparseVector(indices=[1], values=[1.0]), 1, 5)
        assert out[0]["file_id"] == 7

    run(main())


def test_upsert_chunks_stores_chunk_type(monkeypatch):
    client = _FakeClient()
    _fresh(monkeypatch, client)

    async def main():
        await store.upsert_chunks(7, 1, ["a"], [[1.0] * 384], chunk_type="docx")
        assert client.upserted[0].payload["type"] == "docx"

    run(main())


def test_delete_file(monkeypatch):
    client = _FakeClient()
    _fresh(monkeypatch, client)

    async def main():
        await store.delete_file(7, 1)
        assert len(client.deleted) == 1

    run(main())


def test_close_closes_client(monkeypatch):
    """A16：close() 关闭客户端并复位，不再是从不调用的死方法。"""
    client = _FakeClient()
    _fresh(monkeypatch, client)
    store._client = client  # 直接注入 client，验证 close 会调用其 close()

    async def main():
        await store.close()
        assert client.closed is True
        assert store._client is None
        assert store._collection_ready is False

    run(main())


def test_set_embedding_size_used_on_create(monkeypatch):
    """A17：新建 collection 用配置的 embedding 维度（默认 384）。"""
    class _Cap(_FakeClient):
        def __init__(self):
            super().__init__()
            self.vectors_config = None

        async def create_collection(self, name, vectors_config=None, sparse_vectors_config=None):
            self.vectors_config = vectors_config
            await super().create_collection(name, vectors_config=vectors_config, sparse_vectors_config=sparse_vectors_config)

    client = _Cap()
    _fresh(monkeypatch, client)
    store.set_embedding_size(128)

    async def main():
        await store.ensure_collection()
        assert client.vectors_config["dense"].size == 128

    run(main())
    store.set_embedding_size(vs.EMBEDDING_SIZE)  # 复位
