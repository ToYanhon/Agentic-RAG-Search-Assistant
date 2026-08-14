"""LLM 工厂测试（A19）：构造时带 max_tokens，防输出长度不可控。"""

from app.core import llm as llm_mod


def test_build_llm_sets_max_tokens(monkeypatch):
    captured = {}

    class _FakeChat:
        def __init__(self, model=None, api_key=None, base_url=None, temperature=None,
                     timeout=None, max_retries=None, max_tokens=None):
            captured["model"] = model
            captured["max_tokens"] = max_tokens

        def bind_tools(self, tools=None):
            return self

    monkeypatch.setattr(llm_mod, "ChatOpenAI", _FakeChat)
    llm_mod.build_llm(provider="openai", model="m", api_key="k", base_url="http://x")
    assert captured["model"] == "m"
    assert captured["max_tokens"] == llm_mod.settings.llm_max_tokens


def test_llm_max_tokens_default_positive():
    assert isinstance(llm_mod.settings.llm_max_tokens, int)
    assert llm_mod.settings.llm_max_tokens >= 2048
