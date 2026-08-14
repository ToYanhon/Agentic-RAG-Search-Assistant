"""bg_tasks 骨架测试 — 指数退避重试 / 最终失败仅日志 / 成功零重试。"""

import asyncio

import pytest

from app.core.bg_tasks import await_with_retry, run_bg


@pytest.mark.asyncio
async def test_await_with_retry_succeeds_first_try():
    calls = 0

    async def op():
        nonlocal calls
        calls += 1
        return "ok"

    await await_with_retry(op, name="t")
    assert calls == 1


@pytest.mark.asyncio
async def test_await_with_retry_retries_then_succeeds(monkeypatch):
    calls = 0

    async def op():
        nonlocal calls
        calls += 1
        if calls < 3:
            raise RuntimeError("boom")
        return "ok"

    await await_with_retry(op, retries=3, backoff_sec=0.01, name="t")
    assert calls == 3


@pytest.mark.asyncio
async def test_await_with_retry_gives_up_after_retries():
    calls = 0

    async def op():
        nonlocal calls
        calls += 1
        raise RuntimeError("always fail")

    # 不向上抛（最终失败仅日志），retries=2 → 共 3 次
    await await_with_retry(op, retries=2, backoff_sec=0.01, name="t")
    assert calls == 3


@pytest.mark.asyncio
async def test_run_bg_fire_and_forget():
    done = asyncio.Event()

    async def op():
        done.set()

    run_bg(op, name="t")
    await asyncio.wait_for(done.wait(), timeout=1)


@pytest.mark.asyncio
async def test_run_bg_retries_then_succeeds():
    calls = 0
    done = asyncio.Event()

    async def op():
        nonlocal calls
        calls += 1
        if calls < 2:
            raise RuntimeError("boom")
        done.set()

    run_bg(op, retries=3, backoff_sec=0.01, name="t")
    await asyncio.wait_for(done.wait(), timeout=1)
    assert calls == 2


@pytest.mark.asyncio
async def test_run_bg_holds_strong_reference():
    """A5 回归：运行中的任务被 _BG_TASKS 持强引用（防 GC 静默取消），完成后自动移除。"""
    from app.core import bg_tasks

    done = asyncio.Event()

    async def op():
        await asyncio.sleep(0.01)
        done.set()

    task = run_bg(op, name="ref")
    assert task in bg_tasks._BG_TASKS  # 运行中持有强引用
    await asyncio.wait_for(done.wait(), timeout=1)
    await asyncio.wait_for(task, timeout=1)
    await asyncio.sleep(0)  # 让 done 回调有机会执行
    assert task not in bg_tasks._BG_TASKS  # 完成后移除，不泄漏


@pytest.mark.asyncio
async def test_cancelled_propagates():
    started = asyncio.Event()

    async def op():
        started.set()
        await asyncio.sleep(5)

    task = asyncio.ensure_future(
        await_with_retry(op, retries=3, backoff_sec=0.01, name="t")
    )
    await started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
