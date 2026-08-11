"""CloudDrive AI Agent 服务入口。"""

import asyncio
import logging

from fastapi import FastAPI, Request, Response

from app.core import db
from app.core.metrics import http_latency, http_requests, metrics, now
from app.log import setup_logging
from app.router import chat, index, memory, summary

setup_logging()
db.init_db()
logger = logging.getLogger(__name__)

app = FastAPI(title="CloudDrive AI Agent")

app.include_router(chat.router, prefix="/chat", tags=["chat"])
app.include_router(summary.router, prefix="/summary", tags=["summary"])
app.include_router(index.router, prefix="/index", tags=["index"])
app.include_router(memory.router, prefix="/memory", tags=["memory"])

_skills_watch_task = None


@app.on_event("startup")
async def _init_skills():
    # 技能体系：启动即同步全局技能，并启动后台热加载任务
    global _skills_watch_task
    from app.core.skills.watcher import skills_watch_loop

    _skills_watch_task = asyncio.create_task(skills_watch_loop())


@app.on_event("shutdown")
async def _stop_skills():
    global _skills_watch_task
    if _skills_watch_task is not None:
        _skills_watch_task.cancel()
        try:
            await _skills_watch_task
        except asyncio.CancelledError:
            pass
        _skills_watch_task = None


@app.middleware("http")
async def metrics_middleware(request: Request, call_next):
    start = now()
    response = await call_next(request)
    labels = {"method": request.method, "status": str(response.status_code)}
    http_requests.inc(1, labels)
    http_latency.observe(now() - start, {"method": request.method})
    logger.info(
        "http",
        extra={
            "method": request.method,
            "path": request.url.path,
            "status": response.status_code,
            "cost_ms": round((now() - start) * 1000),
            "request_id": request.headers.get("X-Request-Id", ""),
        },
    )
    return response


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/metrics")
def metrics_endpoint():
    return Response(content=metrics.render(), media_type="text/plain")
