"""消息格式转换工具 — dict ↔ LangChain BaseMessage 互转。

消息必须携带稳定 id：Agent 流程的持久化按 id 保证消息稳定、
避免历史索引膨胀（框架依赖 id 去重/更新）。
"""

import uuid
import time

from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)


def dict_to_message(d: dict) -> BaseMessage:
    role = d.get("role", "user")
    content = d.get("content", "")
    msg_id = d.get("id")
    if role == "ai":
        return AIMessage(
            content=content, tool_calls=d.get("tool_calls", []), id=msg_id
        )
    elif role == "tool":
        return ToolMessage(
            content=content, tool_call_id=d.get("tool_call_id", ""), id=msg_id
        )
    elif role == "system":
        return SystemMessage(content=content, id=msg_id)
    return HumanMessage(content=content, id=msg_id)


def message_to_dict(msg: BaseMessage) -> dict:
    d = {
        "role": msg.type,
        "content": msg.content,
        "id": getattr(msg, "id", None) or uuid.uuid4().hex,
        "created_at": int(time.time()),
    }
    if msg.type == "ai":
        tc = getattr(msg, "tool_calls", None)
        if tc:
            d["tool_calls"] = [dict(t) if hasattr(t, "items") else t for t in tc]
        um = getattr(msg, "usage_metadata", None)
        if um:
            d["usage"] = {
                "input_tokens": int(um.get("input_tokens") or 0),
                "output_tokens": int(um.get("output_tokens") or 0),
                "total_tokens": int(um.get("total_tokens") or 0),
            }
    if msg.type == "tool":
        d["tool_call_id"] = getattr(msg, "tool_call_id", "")
    return d
