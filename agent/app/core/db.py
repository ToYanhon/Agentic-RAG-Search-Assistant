"""SQLite 持久化 — 会话与消息的主存储（Redis 仅作读缓存）。

- 单进程单连接，`check_same_thread=False`（uvicorn 当前单 worker）
- WAL 模式降低读写互斥；写操作经 `asyncio.to_thread` 避免阻塞事件循环
- 数据永久保留，无 TTL / 过期
"""

import asyncio
import logging
import sqlite3
from pathlib import Path

from app.config import settings

logger = logging.getLogger(__name__)

_conn: sqlite3.Connection | None = None
_lock = asyncio.Lock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  usage_in INTEGER NOT NULL DEFAULT 0,
  usage_out INTEGER NOT NULL DEFAULT 0,
  cost_yuan REAL NOT NULL DEFAULT 0,
  model TEXT NOT NULL DEFAULT '',
  summary TEXT NOT NULL DEFAULT '',
  compressed_before INTEGER NOT NULL DEFAULT 0,
  fold_msg_id TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id TEXT NOT NULL,
  msg_id TEXT NOT NULL DEFAULT '',
  role TEXT NOT NULL DEFAULT '',
  content TEXT NOT NULL DEFAULT '',
  tool_calls TEXT NOT NULL DEFAULT '',
  tool_call_id TEXT NOT NULL DEFAULT '',
  usage TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, id);

CREATE TABLE IF NOT EXISTS memories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  fact TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(user_id, fact)
);
CREATE INDEX IF NOT EXISTS idx_memories_user ON memories(user_id, id);
"""


def _connect() -> sqlite3.Connection:
    path = settings.db_path
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, check_same_thread=False, timeout=10)
    conn.row_factory = sqlite3.Row
    # autocommit：单条写语句立即落盘（与原先「每次 execute 即 commit」语义一致），
    # 同时允许 execute_tx 用显式 BEGIN/COMMIT 构建真实事务。
    conn.isolation_level = None
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA busy_timeout=5000")
    return conn


def init_db() -> None:
    """建表（幂等），应用启动时调用。"""
    conn = _get_conn()
    conn.executescript(SCHEMA)
    conn.commit()
    # 存量库迁移：补 fold_msg_id 列（摘要折叠缓存的稳定折叠键，见 context_budget）
    try:
        conn.execute("ALTER TABLE sessions ADD COLUMN fold_msg_id TEXT NOT NULL DEFAULT ''")
        conn.commit()
    except sqlite3.OperationalError:
        pass  # 列已存在


def _get_conn() -> sqlite3.Connection:
    global _conn
    if _conn is None:
        _conn = _connect()
    return _conn


async def run(query: str, args: tuple = ()) -> list[dict]:
    """执行查询，返回行字典列表（to_thread，避免阻塞事件循环）。"""
    conn = _get_conn()

    def _exec():
        cur = conn.execute(query, args)
        cols = [d[0] for d in cur.description] if cur.description else None
        rows = cur.fetchall()
        if cols is None:
            return []
        return [dict(zip(cols, (r[i] for i in range(len(cols))))) for r in rows]

    async with _lock:
        return await asyncio.to_thread(_exec)


async def execute(query: str, args: tuple = ()) -> int:
    """执行写操作并提交，返回 rowcount（多语句用 execute_many）。"""
    conn = _get_conn()

    def _exec():
        try:
            cur = conn.execute(query, args)
            conn.commit()
            return cur.rowcount
        except Exception:
            conn.rollback()
            raise

    async with _lock:
        return await asyncio.to_thread(_exec)


async def execute_many(query: str, seq: list[tuple]) -> int:
    """事务内批量写入并提交，返回 rowcount 合计。"""
    conn = _get_conn()

    def _exec():
        try:
            cur = conn.executemany(query, seq)
            conn.commit()
            return cur.rowcount
        except Exception:
            conn.rollback()
            raise

    async with _lock:
        return await asyncio.to_thread(_exec)


async def execute_tx(ops: list[tuple]) -> None:
    """在一个显式事务内顺序执行多条写操作，全部成功或全部回滚（原子）。

    ops 元素为 (sql, args) 走 conn.execute，或 (sql, seq, True) 走 conn.executemany。
    任一步抛错即 ROLLBACK 并向上抛出（调用方可重试）；不留下半状态。
    """
    conn = _get_conn()

    def _exec():
        conn.execute("BEGIN")
        try:
            for op in ops:
                if len(op) == 3:
                    sql, seq, is_many = op
                    if is_many:
                        conn.executemany(sql, seq)
                        continue
                else:
                    sql, args = op
                conn.execute(sql, args)
            conn.execute("COMMIT")
        except Exception:
            try:
                conn.execute("ROLLBACK")
            except Exception:
                pass
            raise

    async with _lock:
        await asyncio.to_thread(_exec)