"""db.execute_tx 事务原语测试 — 隔离临时库 + Redis 旁路。

对应 IMPROVEMENTS.md A2 回归用例：模拟 INSERT 抛错，断言 DELETE 被回滚、数据完整。
"""

import asyncio

import pytest

from app.core import db


@pytest.fixture(autouse=True)
def _fresh_db(tmp_path, monkeypatch):
    monkeypatch.setattr(db, "_conn", None)
    monkeypatch.setattr(db.settings, "db_path", str(tmp_path / "test.db"))
    db.init_db()
    yield


def _run(coro):
    return asyncio.run(coro)


_M_INSERT = (
    "INSERT INTO messages (session_id, msg_id, role, content, tool_calls, tool_call_id, usage, created_at) "
    "VALUES (?,?,?,?,?,?,?,?)"
)


def test_execute_tx_commits_all():
    async def run():
        await db.execute_tx([
            (_M_INSERT, ("s", "m1", "user", "a", "", "", "", 1)),
            (_M_INSERT, ("s", "m2", "ai", "b", "", "", "", 1)),
        ])
        rows = await db.run(
            "SELECT msg_id FROM messages WHERE session_id=? ORDER BY id", ("s",)
        )
        assert [r["msg_id"] for r in rows] == ["m1", "m2"]

    _run(run())


def test_execute_tx_executemany_and_update():
    async def run():
        await db.execute(
            "INSERT INTO sessions (id, user_id, title, created_at, updated_at) VALUES (?,?,?,?,?)",
            ("s1", 1, "t", 1, 1),
        )
        ops: list[tuple] = [
            (_M_INSERT, [
                ("s1", "m1", "user", "a", "", "", "", 1),
                ("s1", "m2", "ai", "b", "", "", "", 1),
            ], True),
            ("UPDATE sessions SET title=? WHERE id=?", ("新标题", "s1")),
        ]
        await db.execute_tx(ops)
        rows = await db.run("SELECT title FROM sessions WHERE id=?", ("s1",))
        assert rows[0]["title"] == "新标题"

    _run(run())


def test_execute_tx_rolls_back_all_on_error():
    """后置语句失败时，事务内已执行的 DELETE 一并回滚，原数据完整（A2）。"""

    async def run():
        await db.execute_tx([
            (_M_INSERT, ("s", "m1", "user", "a", "", "", "", 1)),
            (_M_INSERT, ("s", "m2", "ai", "b", "", "", "", 1)),
        ])
        with pytest.raises(Exception):
            await db.execute_tx([
                ("DELETE FROM messages WHERE session_id=?", ("s",)),
                ("INSERT INTO no_such_table (x) VALUES (?)", (1,)),
            ])
        rows = await db.run(
            "SELECT msg_id FROM messages WHERE session_id=? ORDER BY id", ("s",)
        )
        assert [r["msg_id"] for r in rows] == ["m1", "m2"]

    _run(run())


def test_execute_tx_empty_ops():
    async def run():
        await db.execute_tx([])

    _run(run())
