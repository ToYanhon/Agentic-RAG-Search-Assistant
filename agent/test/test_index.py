"""文件索引（手动 embed）逻辑测试 — 文件夹收集/截断/单文件索引。

用 mock 隔离后端与 embedding，验证 index.py 的路由行为。
"""

import asyncio

import pytest

from app.router import index as idx


def run(coro):
    return asyncio.run(coro)


class _FakeResp:
    def __init__(self, status_code, data):
        self.status_code = status_code
        self._data = data

    def json(self):
        return {"data": self._data}


def _tree():
    return {
        "id": 1,
        "name": "root",
        "files": [{"id": 11, "name": "a.txt"}, {"id": 12, "name": "b.pdf"}],
        "children": [
            {
                "id": 2,
                "name": "sub",
                "files": [{"id": 21, "name": "c.txt"}],
                "children": [],
            }
        ],
    }


def test_folder_files_recursive(monkeypatch):
    class _Client:
        async def get(self, url, params=None, headers=None, timeout=None):
            return _FakeResp(200, _tree())

    async def fake_client():
        return _Client()

    async def fake_token():
        return "tok"

    monkeypatch.setattr(idx, "get_http_client", fake_client)
    monkeypatch.setattr(idx, "get_internal_token", fake_token)

    files = run(idx._folder_files(1, 7))
    assert [f["id"] for f in files] == [11, 12, 21]
    assert files[0]["name"] == "a.txt"


def test_folder_files_error_returns_empty(monkeypatch):
    class _Client:
        async def get(self, url, params=None, headers=None, timeout=None):
            return _FakeResp(404, None)

    async def fake_client():
        return _Client()

    async def fake_token():
        return "tok"

    monkeypatch.setattr(idx, "get_http_client", fake_client)
    monkeypatch.setattr(idx, "get_internal_token", fake_token)

    assert run(idx._folder_files(1, 7)) == []


def _mock_meta(monkeypatch, meta):
    async def fake_meta(fid, uid):
        return meta

    monkeypatch.setattr(idx.rag, "file_meta", fake_meta)


def test_index_one_skips_oversize_via_meta(monkeypatch):
    """A12 回归：元数据显示超大 → 不下载直接跳过。"""
    downloaded = []

    async def fake_download(fid, uid):
        downloaded.append(fid)
        return b"small"

    _mock_meta(monkeypatch, {"name": "big.bin", "size": idx.INDEX_MAX_BYTES + 1})
    monkeypatch.setattr(idx.rag, "download", fake_download)

    result = run(idx._index_one(1, 7, "big.bin"))
    assert result["status"] == "skipped"
    assert result["reason"] == "file too large"
    assert downloaded == []  # 未触发下载


def test_index_one_skips_oversize_fallback(monkeypatch):
    """元数据缺失时回退「下载后判大小」的旧逻辑。"""
    async def fake_download(fid, uid):
        return b"x" * (idx.INDEX_MAX_BYTES + 1)

    _mock_meta(monkeypatch, None)
    monkeypatch.setattr(idx.rag, "download", fake_download)

    result = run(idx._index_one(1, 7, "big.txt"))
    assert result["status"] == "skipped"
    assert result["reason"] == "file too large"


def test_index_one_indexes(monkeypatch):
    async def fake_download(fid, uid):
        return "hello world 测试".encode()

    seen = []

    async def fake_index_file(fid, uid, chunks, chunk_type="text"):
        seen.append(chunk_type)

    _mock_meta(monkeypatch, {"name": "a.txt", "size": 100})
    monkeypatch.setattr(idx.rag, "download", fake_download)
    monkeypatch.setattr(idx.searcher, "index_file", fake_index_file)

    result = run(idx._index_one(1, 7, "a.txt"))
    assert result["status"] == "ok"
    assert result["chunks"] == 1
    assert seen == ["text"]


class _Req:
    def __init__(self, uid):
        self.headers = {"X-User-Id": str(uid)}


def _mock_indexed(monkeypatch, indexed):
    """mock vector_store.file_indexed：只对给定 id 返回已索引。"""
    async def fake_file_indexed(fid, uid):
        return fid in indexed

    monkeypatch.setattr(idx.vector_store, "file_indexed", fake_file_indexed)


def test_indexed_ids(monkeypatch):
    _mock_indexed(monkeypatch, {11, 13})
    indexed = run(idx._indexed_ids(7, [11, 12, 13]))
    assert indexed == {11, 13}


def test_index_status_route(monkeypatch):
    _mock_indexed(monkeypatch, {11})
    req = idx.IndexStatusRequest(files=[11, 12])
    result = run(idx.index_status(req, _Req(7)))
    assert result == {"11": True, "12": False}


def test_unindex_file_route(monkeypatch):
    deleted = []

    async def fake_delete(fid, uid):
        deleted.append(fid)

    monkeypatch.setattr(idx.searcher, "delete_file", fake_delete)
    result = run(idx.unindex_file(5, _Req(7)))
    assert result == {"status": "ok"}
    assert deleted == [5]


def test_folder_status_all_indexed(monkeypatch):
    async def fake_files(folder_id, uid):
        return [{"id": 11, "name": "a.txt"}, {"id": 13, "name": "b.txt"}]

    monkeypatch.setattr(idx, "_folder_files", fake_files)
    _mock_indexed(monkeypatch, {11, 13})

    result = run(idx.index_folder_status(1, _Req(7)))
    assert result["total"] == 2
    assert result["indexed"] == 2
    assert result["all_indexed"] is True


def test_unindex_folder_route(monkeypatch):
    async def fake_files(folder_id, uid):
        return [{"id": 11, "name": "a.txt"}, {"id": 13, "name": "b.txt"}]

    deleted = []

    async def fake_delete(fid, uid):
        deleted.append(fid)

    monkeypatch.setattr(idx, "_folder_files", fake_files)
    monkeypatch.setattr(idx.searcher, "delete_file", fake_delete)

    result = run(idx.unindex_folder(1, _Req(7)))
    assert result == {"status": "ok", "removed": 2}
    assert sorted(deleted) == [11, 13]
