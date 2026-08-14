"""记忆管理路由 — 用户长期记忆的查看/删除（前端 Copilot 记忆面板调用）。

A22：与 chat 其他路由一致，uid==0（缺 X-User-Id）时拒绝，避免误操作 user 0 的记忆。
"""

import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.router.chat import _user_id
from app.service import memory_service

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("")
async def list_memory(request: Request):
    """列出当前用户的长期记忆（fact + created_at）。"""
    uid = _user_id(request)
    if uid == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    entries = await memory_service.get_memory_entries(uid)
    return {"data": entries}


@router.delete("")
async def delete_memory(request: Request, keyword: str):
    """按关键词删除记忆，返回删除条数。"""
    uid = _user_id(request)
    if uid == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    removed = await memory_service.forget_memory(uid, keyword)
    return {"data": {"removed": removed}}
