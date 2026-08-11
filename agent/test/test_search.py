"""语义搜索测试：本地 embedding + Qdrant 稠密/稀疏混合索引。"""

import asyncio
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.core.embedding import searcher


async def main():
    uid = 999

    # 清理
    await searcher.delete_file(101, uid)
    await searcher.delete_file(102, uid)

    print("=== 1. 索引两份文件 ===")
    await searcher.index_file(101, uid, [
        "本月的销售报告显示营收增长了20%，主要来自华东地区",
        "下季度将推出新产品线，预计带来500万营收",
    ])
    await searcher.index_file(102, uid, [
        "招聘计划：需要3名后端工程师，熟悉Go和MySQL",
        "团队扩编到15人，办公地点在深圳",
    ])
    print("已索引 file 101, 102")

    print("\n=== 2. 语义搜索 '销售情况如何' ===")
    r1 = await searcher.search("销售情况如何", uid, top_k=3)
    for r in r1:
        print(f"  file={r['file_id']} score={r['score']:.3f} chunk={r['chunk']}")

    print("\n=== 3. 语义搜索 '招聘后端工程师' ===")
    r2 = await searcher.search("招聘后端工程师", uid, top_k=3)
    for r in r2:
        print(f"  file={r['file_id']} score={r['score']:.3f} chunk={r['chunk']}")

    print("\n=== 4. 用户隔离：其他用户搜不到 ===")
    r3 = await searcher.search("销售报告", 888, top_k=3)
    print(f"  user 888 结果数: {len(r3)} (应=0)")

    print("\n=== 5. 删除文件索引 ===")
    await searcher.delete_file(102, uid)
    r4 = await searcher.search("招聘工程师", uid, top_k=3)
    print(f"  删除102后 '招聘' 结果数: {len(r4)} (应=0)")


if __name__ == "__main__":
    asyncio.run(main())
