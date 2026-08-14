"""进程内后台任务骨架 — 统一的「尽力而为」重试语义。

解决散落的 asyncio.create_task / catch 后仅日志的重复模式：
- run_bg：fire-and-forget 后台任务，失败按指数退避重试，最终失败仅记日志
- await_with_retry：同步等待并重试，最终失败仅记日志（不向上抛）

重试策略对齐 llm_utils：指数退避、上限固定；成功路径零开销。
调用方传 coro_factory（async 可调用）而非协程，便于每次重试重建协程。

run_bg 创建的任务由模块级 _BG_TASKS 持强引用：事件循环对任务只持弱引用，
无强引用的 fire-and-forget 任务可能在执行中被 GC 静默取消（IMPROVEMENTS.md A5）；
任务完成后经 done 回调自动移除，集合不泄漏。
"""

import asyncio
import logging
from collections.abc import Awaitable, Callable

logger = logging.getLogger(__name__)

DEFAULT_RETRIES = 2
DEFAULT_BACKOFF_SEC = 2.0
MAX_BACKOFF_SEC = 8.0

# 正在运行的后台任务强引用集合（防 GC 静默取消；done 时由回调移除）
_BG_TASKS: set[asyncio.Task] = set()


async def _run_with_retry(
    coro_factory: Callable[[], Awaitable],
    retries: int,
    backoff_sec: float,
    name: str,
) -> None:
    """执行 coro_factory()，失败指数退避重试，最终失败仅记日志。"""
    last_exc: BaseException | None = None
    for attempt in range(retries + 1):
        try:
            await coro_factory()
            return
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001 - 尽力而为任务兜底一切异常
            last_exc = e
            if attempt >= retries:
                break
            delay = min(backoff_sec * (2**attempt), MAX_BACKOFF_SEC)
            logger.warning(
                "bg task %s failed (attempt %d/%d), retry in %.1fs: %s",
                name, attempt + 1, retries + 1, delay, e,
            )
            await asyncio.sleep(delay)
    logger.exception("bg task %s gave up after %d attempts", name, retries + 1)


def run_bg(
    coro_factory: Callable[[], Awaitable],
    *,
    retries: int = DEFAULT_RETRIES,
    backoff_sec: float = DEFAULT_BACKOFF_SEC,
    name: str = "bg",
) -> asyncio.Task:
    """启动一个后台任务（fire-and-forget），失败指数退避重试，最终仅记日志。

    调用方不 await（区别于 await_with_retry）；返回 task 便于需要时持有/取消。
    必须在运行中的事件循环里调用（通常由 async 上下文发起）。
    任务由 _BG_TASKS 持强引用，完成后自动移除（A5）。
    """
    task = asyncio.create_task(
        _run_with_retry(coro_factory, retries, backoff_sec, name),
        name=name,
    )
    _BG_TASKS.add(task)
    task.add_done_callback(_BG_TASKS.discard)
    return task


async def await_with_retry(
    coro_factory: Callable[[], Awaitable],
    *,
    retries: int = DEFAULT_RETRIES,
    backoff_sec: float = DEFAULT_BACKOFF_SEC,
    name: str = "op",
) -> None:
    """同步等待执行并重试，最终失败仅记日志（不向上抛）。

    用于主流程中需要「等它完成但不因失败中断」的收尾操作（如消息持久化）。
    """
    await _run_with_retry(coro_factory, retries, backoff_sec, name)
