"""记忆服务测试：写入/读取/去重/遗忘/容量上限/双写持久化/语义去重。

依赖真实 Redis（docker 起 6379）；SQLite 走临时库（避免污染主库）。
"""

import asyncio
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.service import memory_service as ms

# 隔离测试用临时 SQLite
_TMP = tempfile.mkdtemp(prefix="mind_mem_test_")
os.environ["DB_PATH"] = os.path.join(_TMP, "memories.db")


async def main():
    from app.core import db

    db.init_db()
    uid = 998  # 测试用户

    async def _clean():
        await ms.forget_memory(uid, "")
        await db.execute("DELETE FROM memories WHERE user_id = ?", (uid,))

    await _clean()
    print("=== 1. 初始状态 ===")
    print("memories:", await ms.get_memory(uid))

    print("\n=== 2. 写入两条记忆（含 SQLite 双写）===")
    await ms.add_memory(uid, "用户是数据分析师")
    await ms.add_memory(uid, "用户常用英文文档")
    print("memories:", await ms.get_memory(uid))
    rows = await db.run("SELECT fact FROM memories WHERE user_id = ?", (uid,))
    print("sqlite rows:", [r["fact"] for r in rows])

    print("\n=== 3. 去重：重复写入相同事实 ===")
    await ms.add_memory(uid, "用户是数据分析师")
    print("memories (应为2条):", await ms.get_memory(uid))

    print("\n=== 4. 显式遗忘（双库）===")
    removed = await ms.forget_memory(uid, "数据分析")
    print(f"forget '数据分析' 删除 {removed} 条")
    print("memories:", await ms.get_memory(uid))
    rows = await db.run("SELECT fact FROM memories WHERE user_id = ?", (uid,))
    print("sqlite rows:", [r["fact"] for r in rows])

    print("\n=== 5. 容量上限（写入 25 条应只剩 20 条）===")
    for i in range(25):
        await ms.add_memory(uid, f"临时记忆 {i}")
    mems = await ms.get_memory(uid)
    print(f"memories 条数: {len(mems)} (应=20)")
    print("最旧保留(应=临时记忆5):", mems[0])
    print("最新(应=临时记忆24):", mems[-1])

    print("\n=== 6. SQLite 恢复：清空 Redis 后从库恢复 ===")
    r = ms._r()
    await r.delete(ms._key(uid))
    restored = await ms.get_memory(uid)
    print(f"restored 条数: {len(restored)} (应=20)")
    print("restored[0]:", restored[0])

    print("\n=== 7. 语义去重：高相似记忆合并（依赖 embedding，或降级直接写入）===")
    await _clean()
    await ms.add_memory(uid, "用户喜欢喝咖啡")
    await ms.add_memory(uid, "用户是数据分析师")
    before = await ms.get_memory(uid)
    await ms.add_memory_smart(uid, "用户爱喝咖啡")
    after = await ms.get_memory(uid)
    print("before:", before)
    print("after:", after)
    print("(若 LLM 不可用可能降级为直接写入 3 条，属正常降级)")

    print("\n=== 8. 清理测试数据 ===")
    await _clean()
    print("memories:", await ms.get_memory(uid))


if __name__ == "__main__":
    asyncio.run(main())
