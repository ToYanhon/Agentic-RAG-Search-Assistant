"""edit_file_content 工具测试 — 唯一匹配替换 / 多匹配拒绝 / 非文本回退。"""

import asyncio

from app.agent import tools as T


class _Resp:
    def __init__(self, status_code=200, text="", content=b"", headers=None):
        self.status_code = status_code
        self.text = text
        self.content = content
        self.headers = headers or {}


def _run(coro):
    return asyncio.run(coro)


def _patch_get(monkeypatch, responses):
    """_http_get 按调用顺序返回 response。"""
    calls = {"n": 0}

    async def fake_get(path, params, retries=2):
        i = calls["n"]
        calls["n"] += 1
        return responses[i] if i < len(responses) else _Resp(404)

    monkeypatch.setattr(T, "_http_get", fake_get)


def test_edit_unique_replace(monkeypatch):
    _patch_get(monkeypatch, [
        _Resp(200, text="hello world\nfoo again", headers={"content-type": "text/plain"}),
        _Resp(200, text="hello world\nfoo again"),  # download
    ])

    async def fake_send(method, path, params, json=None, retries=2):
        assert method == "PUT"
        assert json["content"] == "hi world\nfoo again"
        return _Resp(200)

    monkeypatch.setattr(T, "_http_send", fake_send)
    tok = T.current_user_id.set(1)
    try:
        r = _run(T.edit_file_content.ainvoke({"file_id": 5, "old_string": "hello", "new_string": "hi"}))
    finally:
        T.current_user_id.reset(tok)
    assert "已替换并保存" in r


def test_edit_multiple_match_rejected(monkeypatch):
    _patch_get(monkeypatch, [
        _Resp(200, text="hello\nhello\nhello", headers={"content-type": "text/plain"}),
        _Resp(200, text="hello\nhello\nhello"),
    ])
    tok = T.current_user_id.set(1)
    try:
        r = _run(T.edit_file_content.ainvoke({"file_id": 5, "old_string": "hello", "new_string": "hi"}))
    finally:
        T.current_user_id.reset(tok)
    assert "需唯一匹配" in r
