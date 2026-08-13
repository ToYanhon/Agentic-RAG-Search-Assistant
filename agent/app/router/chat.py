"""Agent 对话路由 — session CRUD + AgentWorkflow SSE 流式回复。

路由层只做薄封装：解析请求头（用户/AI 配置/联网 key）→ 设置请求级 contextvar
→ 消费 AgentWorkflow.turn() 的事件流转 SSE 文本。回合内全部编排逻辑在
app.agent.workflow（历史/记忆/技能/上下文/路由/工具/持久化/记忆提炼）。
"""

import json
import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse, StreamingResponse

from app.agent.tools import current_user_id, tavily_api_key
from app.agent.workflow import get_workflow
from app.core.llm_override import LLMOverride, reset_llm_override, set_llm_override
from app.model.chat import AppendMessagesRequest, ChatRequest, CreateSessionRequest, RenameSessionRequest
from app.service import session_service as svc

logger = logging.getLogger(__name__)

router = APIRouter()


def _user_id(request: Request) -> int:
    uid = request.headers.get("X-User-Id", "0")
    return int(uid) if uid.isdigit() else 0


def _llm_override(request: Request) -> LLMOverride:
    """从请求头读取前端 AI 设置（provider / baseUrl / apiKey / model），未传则保持 None。"""
    return LLMOverride(
        provider=request.headers.get("X-LLM-Provider") or None,
        base_url=request.headers.get("X-LLM-Base-URL") or None,
        api_key=request.headers.get("X-LLM-Key") or None,
        model=request.headers.get("X-LLM-Model") or None,
    )


def _to_sse(ev: dict) -> str:
    """事件 dict → SSE 行。done 用 [DONE] 结束标记（与旧实现一致）。"""
    if ev.get("type") == "done":
        return "data: [DONE]\n\n"
    return f"data: {json.dumps(ev, ensure_ascii=False)}\n\n"


@router.post("/sessions")
async def create_session(req: CreateSessionRequest, request: Request):
    user_id = _user_id(request)
    if user_id == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    sid = await svc.create_session(user_id, req.title or "新对话")
    return {"data": {"id": sid}}


@router.get("/sessions")
async def list_sessions(request: Request, page: int = 1, page_size: int = 50):
    user_id = _user_id(request)
    if user_id == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    result = await svc.list_sessions(user_id, page, page_size)
    return {"data": result}


@router.delete("/sessions/{session_id}")
async def delete_session(session_id: str, request: Request):
    user_id = _user_id(request)
    ok = await svc.delete_session(session_id, user_id)
    if not ok:
        return JSONResponse({"error": "session not found"}, status_code=404)
    return {"data": "ok"}


@router.put("/sessions/{session_id}")
async def rename_session(session_id: str, req: RenameSessionRequest, request: Request):
    user_id = _user_id(request)
    ok = await svc.rename_session(session_id, req.title, user_id)
    if not ok:
        return JSONResponse({"error": "session not found"}, status_code=404)
    return {"data": "ok"}


@router.get("/sessions/{session_id}/messages")
async def get_messages(session_id: str, request: Request):
    user_id = _user_id(request)
    owner = await svc.get_session_owner(session_id)
    if owner != user_id:
        return JSONResponse({"error": "session not found"}, status_code=404)
    msgs = await svc.get_messages(session_id)
    return {"data": msgs}


@router.post("/sessions/{session_id}/messages/append")
async def append_messages(session_id: str, req: AppendMessagesRequest, request: Request):
    """直接向会话追加消息（不触发 LLM 工作流），用于写入预生成的摘要对等。

    仅校验归属与字段白名单；调用方需保证内容可信（agent 仅后端可访问）。
    """
    user_id = _user_id(request)
    if user_id == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)
    owner = await svc.get_session_owner(session_id)
    if owner != user_id:
        return JSONResponse({"error": "session not found"}, status_code=404)
    if not req.messages:
        return JSONResponse({"error": "empty messages"}, status_code=400)
    await svc.add_messages(
        session_id,
        [
            {
                "role": m.get("role", "user"),
                "content": m.get("content", ""),
            }
            for m in req.messages
        ],
    )
    return {"data": "ok"}


@router.post("/sessions/{session_id}/messages")
async def send_message(session_id: str, req: ChatRequest, request: Request):
    user_id = _user_id(request)
    if user_id == 0:
        return JSONResponse({"error": "unauthorized"}, status_code=401)

    owner = await svc.get_session_owner(session_id)
    if owner != user_id:
        return JSONResponse({"error": "session not found"}, status_code=404)

    # 聊天必须携带用户自己的 AI 配置（Base URL / API Key / 模型 三项齐全），否则不落服务端默认。
    override = _llm_override(request)
    if not (override.base_url and override.api_key and override.model):
        return JSONResponse(
            {"error": "AI not configured, please set base_url/api_key/model in settings"},
            status_code=400,
        )

    # 请求级 contextvar：override / user_id / tavily key 在整回合（含 workflow 内
    # 的摘要折叠、工具调用、后台记忆提炼）期间对异步上下文可见，结束后复位。
    token = current_user_id.set(user_id)
    token_ov = set_llm_override(override)
    token_tav = tavily_api_key.set(request.headers.get("X-Tavily-Key", ""))

    async def event_stream():
        try:
            async for ev in get_workflow().turn(user_id, session_id, req.message):
                yield _to_sse(ev)
        finally:
            current_user_id.reset(token)
            reset_llm_override(token_ov)
            tavily_api_key.reset(token_tav)

    return StreamingResponse(event_stream(), media_type="text/event-stream")
