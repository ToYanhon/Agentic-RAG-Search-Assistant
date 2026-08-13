"""Agent 服务各接口的请求/响应数据模型定义。"""

from pydantic import BaseModel


class ChatRequest(BaseModel):
    message: str


class CreateSessionRequest(BaseModel):
    title: str | None = None


class RenameSessionRequest(BaseModel):
    title: str


class AppendMessagesRequest(BaseModel):
    """直接向会话追加消息（不触发 LLM 工作流），供前端写入预生成的摘要等。"""
    messages: list[dict]


class SearchRequest(BaseModel):
    query: str
    user_id: int
    top_k: int = 5


class SearchResult(BaseModel):
    file_id: int
    file_name: str = ""
    score: float
    chunk: str = ""


class SearchResponse(BaseModel):
    results: list[SearchResult]
