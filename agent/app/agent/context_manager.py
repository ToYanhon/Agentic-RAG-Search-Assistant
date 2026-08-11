"""ContextManager — 上下文预算与历史折叠（包装 context_budget）。

multi-agent 后系统提示词由各 worker/supervisor 自注入，此处只负责
把超窗口的早期历史折叠成摘要，返回进入上下文的消息序列。
"""

from app.core.context_budget import BudgetResult, build_context
from app.core.llm import current_model, current_provider
from app.core.model_meta import model_meta


class ContextManager:
    async def build(
        self,
        history: list[dict],
        message: str,
        session_id: str | None = None,
    ) -> BudgetResult:
        window = model_meta(current_provider(), current_model()).context_window
        return await build_context(
            history=history,
            human_text=message,
            window=window,
            session_id=session_id,
        )
