"""session_service SQLite 持久化测试 — 隔离临时库 + Redis 旁路。"""

import time

import pytest

from app.core import db
from app.service import session_service as svc


@pytest.fixture(autouse=True)
def _fresh_db(tmp_path, monkeypatch):
    monkeypatch.setattr(db, "_conn", None)
    monkeypatch.setattr(db.settings, "db_path", str(tmp_path / "test.db"))
    db.init_db()
    yield


@pytest.fixture(autouse=True)
def _redis_down(monkeypatch):
    """Redis 不可用 → 缓存层内部吞错旁路（功能不依赖 Redis）。"""

    async def boom(*a, **k):
        raise ConnectionError("redis down")

    monkeypatch.setattr(svc, "_r", boom)


async def _mk(uid: int = 1, title: str = "t") -> str:
    return await svc.create_session(uid, title)


def test_create_and_owner():
    import asyncio

    async def run():
        sid = await _mk(7)
        assert await svc.get_session_owner(sid) == 7
        assert await svc.get_session_owner("missing") is None

    asyncio.run(run())


def test_messages_roundtrip_and_replace():
    import asyncio

    async def run():
        sid = await _mk(7)
        await svc.add_messages(sid, [
            {"role": "user", "content": "hi", "id": None},
            {"role": "ai", "content": "yo", "id": "abc"},
        ])
        msgs = await svc.get_messages(sid)
        assert [m["role"] for m in msgs] == ["user", "ai"]
        assert msgs[0]["rowid"] < msgs[1]["rowid"]

        await svc.replace_messages(sid, [{"role": "user", "content": "new"}])
        msgs2 = await svc.get_messages(sid)
        assert len(msgs2) == 1 and msgs2[0]["content"] == "new"

    asyncio.run(run())


def test_usage_accumulates():
    import asyncio

    async def run():
        sid = await _mk(7)
        await svc.add_usage(sid, 100, 20, 0.001, "deepseek-chat")
        await svc.add_usage(sid, 300, 60, 0.002, "deepseek-chat")
        rows = await db.run("SELECT usage_in, usage_out, cost_yuan FROM sessions WHERE id=?", (sid,))
        assert rows[0]["usage_in"] == 400
        assert rows[0]["cost_yuan"] == pytest.approx(0.003)
        await svc.add_usage("nope", 1, 0, 0, "x")  # 不存在会话忽略

    asyncio.run(run())


def test_delete_owner_isolated():
    import asyncio

    async def run():
        sid = await _mk(1)
        assert not await svc.delete_session(sid, 2)
        assert await svc.get_session_owner(sid) == 1
        assert await svc.delete_session(sid, 1)
        assert await svc.get_session_owner(sid) is None
        assert await svc.get_messages(sid) == []

    asyncio.run(run())


def test_rename_owner_and_list():
    import asyncio

    async def run():
        sid = await _mk(1, "s1")
        assert not await svc.rename_session(sid, "x", 9)
        assert await svc.rename_session(sid, "新标题", 1)
        lst = await svc.list_sessions(1)
        assert lst["items"][0]["title"] == "新标题"

    asyncio.run(run())


def test_list_sorted_by_updated_and_preview():
    import asyncio

    async def run():
        await _mk(1, "第一")
        time.sleep(1.1)
        sid2 = await _mk(1, "第二")
        await svc.replace_messages(sid2, [{"role": "user", "content": "你好世界" * 30}])
        time.sleep(1.1)
        sid3 = await _mk(1, "第三")
        await svc.replace_messages(sid3, [{"role": "ai", "content": "最新活动占位" * 20}])

        lst = await svc.list_sessions(1)
        titles = [i["title"] for i in lst["items"]]
        assert titles == ["第三", "第二", "第一"]
        top = lst["items"][0]
        assert top["message_count"] == 1
        assert "最新活动占位" in top["last_preview"]

    asyncio.run(run())


def test_summary_cache():
    import asyncio

    async def run():
        sid = await _mk(1)
        assert await svc.get_session_summary(sid) is None
        await svc.set_session_summary(sid, "摘要内容", "m42")
        cached = await svc.get_session_summary(sid)
        assert cached == ("摘要内容", "m42")

    asyncio.run(run())