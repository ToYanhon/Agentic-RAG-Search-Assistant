"""Agent 工具定义 — @tool 装饰器，自动生成 Function Calling schema。

故障恢复策略：
  - safe_tool: 工具异常转成错误字符串返回给 LLM，由 LLM 决定 fallback，不中断 Agent 流程
  - _http_get: 超时 + 指数退避重试，抵御后端瞬时故障
"""

import asyncio
import contextvars
import functools
from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

import httpx
from langchain_core.tools import tool

from app.auth_token import get_internal_token, reset_token_cache
from app.config import settings
from app.core.embedding import searcher
from app.core.metrics import now, tool_calls, tool_latency

F = TypeVar("F", bound=Callable[..., Awaitable[Any]])

current_user_id: contextvars.ContextVar[int] = contextvars.ContextVar(
    "current_user_id", default=0
)

tavily_api_key: contextvars.ContextVar[str] = contextvars.ContextVar(
    "tavily_api_key", default=""
)

TAVILY_ENDPOINT = "https://api.tavily.com/search"
TAVILY_MAX_RESULTS = 5


def safe_tool(func: F) -> F:
    """工具执行兜底：异常转为错误字符串，避免中断 Agent 流程；同时打点。"""

    @functools.wraps(func)
    async def wrapper(*args: Any, **kwargs: Any) -> Any:
        labels = {"name": func.__name__}
        start = now()
        try:
            result = await func(*args, **kwargs)
            tool_calls.inc(1, {**labels, "status": "success"})
            return result
        except Exception as e:  # noqa: BLE001 - 工具层需要兜住一切异常
            tool_calls.inc(1, {**labels, "status": "error"})
            return f"[工具执行失败: {type(e).__name__}: {e}]"
        finally:
            tool_latency.observe(now() - start, labels)

    return wrapper  # type: ignore[return-value]


async def _http_get(path: str, params: dict, retries: int = 2) -> httpx.Response | None:
    """带超时与重试的 GET，网络错误/5xx 时指数退避重试。

    401 时强制刷新内部 token 并重试一次，规避 token 轮换间隙。
    最终失败返回 None。
    """
    token = await get_internal_token()
    last: httpx.Response | None = None
    for attempt in range(retries + 2):
        try:
            async with httpx.AsyncClient(
                headers={"X-Agent-Token": token}, timeout=10.0, trust_env=False
            ) as client:
                resp = await client.get(f"{settings.backend_url}{path}", params=params)
                if resp.status_code == 401:
                    # 内部 token 可能已轮换：重置缓存取最新 token 重试一次
                    token = await get_internal_token(force_refresh=True)
                    if attempt == 0:
                        continue
                    return resp
                if resp.status_code < 500:
                    return resp
                last = resp
        except httpx.HTTPError:
            last = None
        if attempt < retries:
            await asyncio.sleep(0.5 * (2**attempt))
    return last


async def _tavily_search(query: str, api_key: str) -> dict | None:
    """调用 Tavily 搜索 API，返回原始 JSON；失败返回 None。"""
    try:
        async with httpx.AsyncClient(timeout=15.0, trust_env=False) as client:
            resp = await client.post(
                TAVILY_ENDPOINT,
                json={
                    "api_key": api_key,
                    "query": query,
                    "max_results": TAVILY_MAX_RESULTS,
                    "search_depth": "basic",
                    "include_answer": True,
                },
            )
    except httpx.HTTPError:
        return None
    if resp.status_code != 200:
        return None
    return resp.json()


@tool
@safe_tool
async def web_search(query: str) -> list[dict]:
    """搜索互联网获取实时信息（新闻、百科、最新动态等）。

    返回的每条结果含 title/url/content/score/published_date（若有），
    以及 Tavily 合成的 answer 结论。引用任何数字/涨跌幅/点位前，
    必须先在返回结果中核实到对应出处；若数据未在结果中出现，明确告知
    用户"未找到该数据"，严禁编造或臆测数字。
    """
    key = tavily_api_key.get()
    if not key:
        return [{"error": "未配置 Tavily API Key（设置 → AI 配置 → Tavily API Key）"}]
    data = await _tavily_search(query, key)
    if not data:
        return [{"error": "联网搜索失败"}]
    out = []
    if data.get("answer"):
        out.append({"answer": data["answer"]})
    for r in (data.get("results") or [])[:TAVILY_MAX_RESULTS]:
        item: dict = {
            "title": r.get("title", ""),
            "url": r.get("url", ""),
            "content": (r.get("content") or "")[:300],
        }
        if r.get("score") is not None:
            item["score"] = round(float(r["score"]), 3)
        if r.get("published_date"):
            item["published_date"] = r["published_date"]
        out.append(item)
    return out


@tool
@safe_tool
async def search_files(query: str) -> list[dict]:
    """按文件名关键词搜索用户的文件。"""
    uid = current_user_id.get()
    resp = await _http_get("/files/search", {"q": query, "user_id": uid})
    if resp and resp.status_code == 200:
        return resp.json().get("data", {}).get("files", [])
    return []


@tool
@safe_tool
async def semantic_search(query: str) -> list[dict]:
    """按语义搜索文件内容，返回最相关的文件片段（含文件ID和内容摘要）。"""
    uid = current_user_id.get()
    return await searcher.search(query, uid, top_k=5)


@tool
@safe_tool
async def read_file_content(file_id: int) -> str:
    """读取文件的文本内容（支持 txt/pdf/docx）。"""
    uid = current_user_id.get()
    resp = await _http_get(f"/files/{file_id}/download", {"user_id": uid})
    if resp and resp.status_code == 200:
        ct = resp.headers.get("content-type", "")
        return f"[文件 {file_id} 不是文本类型]" if "text" not in ct else resp.text
    return f"[无法读取文件 {file_id}]"


@tool
@safe_tool
async def summarize_file(file_id: int) -> str:
    """总结文件的核心内容。"""
    uid = current_user_id.get()
    from app.core.rag import RAGEngine

    rag = RAGEngine()
    summary = await rag.summarize(file_id, uid)
    return summary or "无法总结该文件"


@tool
@safe_tool
async def list_folder(folder_id: int) -> dict:
    """查看文件夹中的文件和子文件夹列表。"""
    uid = current_user_id.get()
    if folder_id == 0:
        # 根目录：文件夹 + 根目录直属文件（/folders/root 不含文件）
        folders_resp = await _http_get("/folders/root", {"user_id": uid})
        files_resp = await _http_get("/files", {"user_id": uid})
        return {
            "folders": (
                folders_resp.json().get("data", [])
                if folders_resp and folders_resp.status_code == 200
                else []
            ),
            "files": (
                files_resp.json().get("data", {}).get("files", [])
                if files_resp and files_resp.status_code == 200
                else []
            ),
        }
    resp = await _http_get(f"/folders/{folder_id}", {"user_id": uid})
    if resp and resp.status_code == 200:
        return resp.json().get("data", {})
    return {"error": "folder not found"}


@tool
@safe_tool
async def get_file_info(file_id: int) -> dict:
    """获取文件的详细信息（名称、大小、类型、创建时间等）。"""
    uid = current_user_id.get()
    resp = await _http_get(f"/files/{file_id}", {"user_id": uid})
    if resp and resp.status_code == 200:
        return resp.json().get("data", {})
    return {"error": "file not found"}


@tool
@safe_tool
async def save_memory(fact: str) -> str:
    """保存一条关于用户的长期记忆（偏好、身份、习惯等），跨会话长期生效。"""
    uid = current_user_id.get()
    from app.service import memory_service

    return await memory_service.add_memory_smart(uid, fact)


@tool
@safe_tool
async def forget_memory(keyword: str) -> str:
    """删除包含关键词的长期记忆（当用户明确表示之前记错了/想忘记时调用）。"""
    uid = current_user_id.get()
    from app.service import memory_service

    removed = await memory_service.forget_memory(uid, keyword)
    return f"已删除 {removed} 条相关记忆" if removed else "没有找到相关的长期记忆"


@tool
@safe_tool
async def get_storage_usage() -> dict:
    """查看网盘存储空间使用情况（已用/总容量/剩余字节）。"""
    uid = current_user_id.get()
    resp = await _http_get("/auth/storage/usage", {"user_id": uid})
    if resp and resp.status_code == 200:
        d = resp.json().get("data", {})
        used = d.get("storage_used", 0)
        limit = d.get("storage_limit", 0)
        return {
            "storage_used": used,
            "storage_limit": limit,
            "storage_remaining": max(0, limit - used),
        }
    return {"error": "获取存储信息失败"}


@tool
@safe_tool
async def get_memory() -> str:
    """列出当前用户的长期记忆（偏好、身份、习惯等），用于核对/回忆已知信息。"""
    uid = current_user_id.get()
    from app.service import memory_service

    memories = await memory_service.get_memory(uid)
    if not memories:
        return "暂无长期记忆"
    return "\n".join(f"- {m}" for m in memories)


# ---- 工具集分组（按 worker 职责）----
FILE_TOOLS = [
    search_files,
    semantic_search,
    read_file_content,
    summarize_file,
    list_folder,
    get_file_info,
]
WEB_TOOLS = [web_search]
MEMORY_TOOLS = [save_memory, forget_memory, get_memory]
GENERAL_TOOLS = [get_storage_usage]

# 基础工具全集（ToolManager 注册用）
tools = FILE_TOOLS + WEB_TOOLS + MEMORY_TOOLS + GENERAL_TOOLS
