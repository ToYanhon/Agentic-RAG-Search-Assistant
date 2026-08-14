"""文件索引路由 — 手动触发（前端 embed 按钮），提取文本 + 生成 Embedding。

- POST   /index/{file_id}            单个文件建索引
- DELETE /index/{file_id}            取消单文件索引
- POST   /index/folder/{folder_id}   递归索引该文件夹下所有文件（并发 + 上限）
- DELETE /index/folder/{folder_id}   递归取消该文件夹下所有文件索引
- POST   /index/status               批量查询文件索引状态 {id: bool}
- POST   /index/folder/{id}/status   查询文件夹下全部文件索引状态
"""

import asyncio
import logging

import httpx
from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from app.auth_token import get_internal_token
from app.config import settings
from app.core.embedding import searcher
from app.core.http import get_http_client
from app.core.llm_override import LLMOverride, reset_llm_override, set_llm_override
from app.core.rag import RAGEngine, detect_kind
from app.core.vector_store import vector_store

router = APIRouter()
rag = RAGEngine()
logger = logging.getLogger(__name__)

INDEX_MAX_FILES = 100
INDEX_MAX_BYTES = 100 * 1024 * 1024  # 单文件超 100MB 跳过
FOLDER_INDEX_CONCURRENCY = 4


class IndexStatusRequest(BaseModel):
    files: list[int]


def _uid(request: Request) -> int:
    h = request.headers.get("X-User-Id", "0")
    return int(h) if h.isdigit() else 0


def _llm_override(request: Request) -> LLMOverride:
    """从请求头读取前端 AI 设置（provider / baseUrl / apiKey / model），未传则保持 None。"""
    return LLMOverride(
        provider=request.headers.get("X-LLM-Provider") or None,
        base_url=request.headers.get("X-LLM-Base-URL") or None,
        api_key=request.headers.get("X-LLM-Key") or None,
        model=request.headers.get("X-LLM-Model") or None,
    )


def _with_llm_override(request: Request, coro):
    """在请求级 LLM 覆盖（图片视觉描述等）生效下执行 coro，结束后复位。"""

    async def _wrapped():
        token = set_llm_override(_llm_override(request))
        try:
            return await coro
        finally:
            reset_llm_override(token)

    return _wrapped()


async def _index_one(file_id: int, uid: int, name: str = "") -> dict:
    """为单个文件建索引。返回 {status: ok|skipped|failed, chunks, reason}。

    先取后端元数据（name + size），size 超限直接跳过，避免全量下载（IMPROVEMENTS.md A12）；
    元数据读取失败时回退「下载后判大小」的旧逻辑。
    """
    filename = name
    meta = await rag.file_meta(file_id, uid)
    if meta is not None:
        filename = name or (meta.get("name") or "")
        size = meta.get("size") or 0
        if size > INDEX_MAX_BYTES:
            return {"status": "skipped", "reason": "file too large"}
    content = await rag.download(file_id, uid)
    if content is None:
        return {"status": "failed", "reason": "file not found"}
    if len(content) > INDEX_MAX_BYTES:
        return {"status": "skipped", "reason": "file too large"}
    # 图片的 LLM 视觉描述会调用用户配置的模型：提取放线程池，避免阻塞事件循环
    text = await asyncio.to_thread(rag.extract_text, content, filename)
    if not text.strip():
        return {"status": "skipped", "reason": "unsupported or empty file"}
    chunks = rag.chunk_text(text, chunk_size=500)
    await searcher.index_file(file_id, uid, chunks, chunk_type=detect_kind(filename))
    return {"status": "ok", "chunks": len(chunks)}


async def _folder_files(folder_id: int, uid: int) -> list[dict]:
    """调后端文件夹树接口，递归收集该文件夹下所有文件 {id, name}。"""
    client = await get_http_client()
    token = await get_internal_token()
    try:
        resp = await client.get(
            f"{settings.backend_url}/folders/{folder_id}",
            params={"user_id": uid},
            headers={"X-Agent-Token": token},
            timeout=15.0,
        )
        if resp.status_code != 200:
            return []
        data = (resp.json().get("data") or {})
    except (httpx.HTTPError, ValueError):
        return []

    files: list[dict] = []

    def walk(node: dict) -> None:
        for f in node.get("files") or []:
            files.append({"id": f.get("id"), "name": f.get("name", "")})
        for child in node.get("children") or []:
            walk(child)

    walk(data)
    return [f for f in files if f.get("id")]


async def _indexed_ids(uid: int, file_ids: list[int]) -> set[int]:
    """批量判断哪些文件已建立索引（Qdrant count，并发受限）。"""
    if not file_ids:
        return set()
    sem = asyncio.Semaphore(FOLDER_INDEX_CONCURRENCY)

    async def one(fid: int) -> tuple[int, bool]:
        async with sem:
            try:
                return fid, await vector_store.file_indexed(fid, uid)
            except Exception:
                return fid, False

    pairs = await asyncio.gather(*(one(fid) for fid in file_ids))
    return {fid for fid, ok in pairs if ok}


@router.post("/status")
async def index_status(req: IndexStatusRequest, request: Request):
    """批量查询文件索引状态，返回 {"<id>": true|false}。"""
    uid = _uid(request)
    if uid == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    indexed = await _indexed_ids(uid, req.files)
    return {str(fid): fid in indexed for fid in req.files}


@router.delete("/{file_id}")
async def unindex_file(file_id: int, request: Request):
    """取消单个文件的索引。"""
    uid = _uid(request)
    if uid == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    await searcher.delete_file(file_id, uid)
    return {"status": "ok"}


@router.post("/folder/{folder_id}/status")
async def index_folder_status(folder_id: int, request: Request):
    """查询文件夹下全部文件索引状态。"""
    uid = _uid(request)
    if uid == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    files = await _folder_files(folder_id, uid)
    indexed = await _indexed_ids(uid, [f["id"] for f in files])
    total = len(files)
    return {"status": "ok", "total": total, "indexed": len(indexed), "all_indexed": total > 0 and len(indexed) == total}


@router.delete("/folder/{folder_id}")
async def unindex_folder(folder_id: int, request: Request):
    """递归取消该文件夹下所有文件索引。"""
    uid = _uid(request)
    if uid == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    files = await _folder_files(folder_id, uid)
    for f in files:
        await searcher.delete_file(f["id"], uid)
    return {"status": "ok", "removed": len(files)}


@router.post("/folder/{folder_id}")
async def index_folder(folder_id: int, request: Request):
    """递归索引文件夹下所有文件（并发受限，超上限截断）。"""
    uid = _uid(request)
    if uid == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)

    files = await _folder_files(folder_id, uid)
    total = len(files)
    truncated = max(0, total - INDEX_MAX_FILES)
    files = files[:INDEX_MAX_FILES]

    sem = asyncio.Semaphore(FOLDER_INDEX_CONCURRENCY)

    async def one(f: dict) -> dict:
        async with sem:
            return await _index_one(f["id"], uid, f.get("name", ""))

    # 请求级 LLM 覆盖贯穿整组索引：图片视觉描述用用户配置的模型
    results = await _with_llm_override(
        request, asyncio.gather(*(one(f) for f in files))
    )
    counts = {"ok": 0, "skipped": 0, "failed": 0}
    for r in results:
        counts[r["status"]] += 1
    return {
        "status": "ok",
        "total": total,
        "indexed": counts["ok"],
        "skipped": counts["skipped"],
        "failed": counts["failed"],
        "truncated": truncated,
    }


@router.post("/{file_id}")
async def index_file(file_id: int, request: Request, filename: str = ""):
    uid = _uid(request)
    if uid == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    result = await _with_llm_override(request, _index_one(file_id, uid, filename))
    if result["status"] == "failed":
        return JSONResponse(result, status_code=404)
    return result
