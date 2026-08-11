"""文件摘要路由 — 前端「AI 总结」按钮调用。

依赖请求级 LLM 覆盖（X-LLM-* 头），生成摘要的调用遵循用户自己的 AI 配置。
"""

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse

from app.core.rag import RAGEngine

router = APIRouter()
rag = RAGEngine()


def _require_ai_config(request: Request) -> None:
    """摘要走 LLM，须携带用户 AI 配置（Base URL / API Key / 模型 三项齐全）。"""
    if not (
        request.headers.get("X-LLM-Base-URL")
        and request.headers.get("X-LLM-Key")
        and request.headers.get("X-LLM-Model")
    ):
        raise HTTPException(
            status_code=400,
            detail="AI not configured, please set base_url/api_key/model in settings",
        )


@router.post("/{file_id}")
async def summary(file_id: int, user_id: int, request: Request):
    uid_header = request.headers.get("X-User-Id", "0")
    uid = int(uid_header) if uid_header.isdigit() else 0
    if uid == 0 or uid != user_id:
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    _require_ai_config(request)
    text = await rag.summarize(file_id, user_id)
    if text is None:
        raise HTTPException(status_code=404, detail="file not found or unsupported")
    return {"summary": text}
