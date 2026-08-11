"""Agent 日志配置 — 结构化 JSON，输出到终端 + 滚动文件。

文件按「天」轮转（保留 backup_days 天），且单个文件超 max_bytes 时额外滚动。
"""

import json
import logging
import os
from datetime import datetime

_LTZ = datetime.now().astimezone().tzinfo

from logging.handlers import RotatingFileHandler, TimedRotatingFileHandler

LOG_DIR = os.getenv("LOG_DIR", "./logs")
MAX_BYTES = int(os.getenv("LOG_MAX_BYTES", str(100 * 1024 * 1024)))  # 100MB
BACKUP_DAYS = int(os.getenv("LOG_BACKUP_DAYS", "7"))


class JsonFormatter(logging.Formatter):
    """JSON 格式日志行：{time, level, logger, message}。"""

    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "time": datetime.fromtimestamp(record.created, tz=_LTZ).strftime(
                "%Y-%m-%dT%H:%M:%S"
            ),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        if record.exc_info:
            payload["exc_info"] = self.formatException(record.exc_info)
        for key in (
            "method",
            "status",
            "path",
            "user_id",
            "session_id",
            "cost_ms",
            "request_id",
        ):
            v = getattr(record, key, None)
            if v is not None:
                payload[key] = v
        return json.dumps(payload, ensure_ascii=False)


def setup_logging() -> None:
    os.makedirs(LOG_DIR, exist_ok=True)
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    if root.handlers:
        return

    fmt = JsonFormatter()
    # 终端输出
    console = logging.StreamHandler()
    console.setFormatter(fmt)
    root.addHandler(console)

    # 文件输出：按天轮转 + 按大小二次轮转
    file_handler = TimedRotatingFileHandler(
        os.path.join(LOG_DIR, "agent.log"),
        when="midnight",
        backupCount=BACKUP_DAYS,
        encoding="utf-8",
    )
    file_handler.suffix = "%Y-%m-%d"
    file_handler.setFormatter(fmt)
    root.addHandler(file_handler)

    # 按大小限制：对当天文件超限滚动（附加 .1/.2 后缀）
    size_handler = RotatingFileHandler(
        os.path.join(LOG_DIR, "agent-size.log"),
        maxBytes=MAX_BYTES,
        backupCount=5,
        encoding="utf-8",
    )
    size_handler.setFormatter(fmt)
    root.addHandler(size_handler)

    logging.getLogger("uvicorn").setLevel(logging.INFO)
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)


def log_extra(record: logging.LogRecord) -> None:
    """占位：供需附带结构化字段的日志调用使用（见 slog 对应能力）。"""
