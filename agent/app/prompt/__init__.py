"""Prompt 统一出口 — 全部提示词文本与构建函数收敛到 app.prompt.prompts。

消费方既可 `from app.prompt.prompts import X`，也可经本包 `from app.prompt import X`。
"""

from app.prompt.prompts import (  # noqa: F401
    DOC_SUMMARY_PROMPT,
    DEDUP_PROMPT,
    EXTRACT_PROMPT,
    SKILL_CONTEXT_GUARD,
    SUMMARIZE_PROMPT,
    SUPERVISOR_SYSTEM_PROMPT,
    WORKER_PROMPTS,
    build_supervisor_prompt,
    build_worker_prompt,
    current_time_context,
)
