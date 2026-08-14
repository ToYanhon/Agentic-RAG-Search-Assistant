"""全局技能目录热加载 — 后台定时扫描，按 mtime 增量同步 registry。"""

import asyncio
import logging

from app.config import settings
from app.core.skills.global_loader import sync_global

logger = logging.getLogger(__name__)


async def skills_watch_loop() -> None:
    """周期性扫描全局技能目录并同步 registry（首轮立即扫描，随后按间隔轮询）。"""
    logger.info(
        "skills watcher started: %s every %ds",
        settings.skills_dir,
        settings.skills_scan_interval_sec,
    )
    while True:
        try:
            # 目录扫描是同步文件 IO，放线程池避免阻塞事件循环（A9）
            await asyncio.to_thread(sync_global)
        except Exception:  # noqa: BLE001 - 扫描失败仅记日志，下轮重试
            logger.exception("skills rescan failed")
        await asyncio.sleep(settings.skills_scan_interval_sec)
