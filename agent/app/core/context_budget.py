"""AI 上下文预算 — 入上下文的 token 估算与裁剪，超预算部分增量摘要折叠。

流程（chat.py 组装阶段调用）：
1. 按上下文窗口留输出余量，得到输入预算
2. 历史按「组」从最新往回贪心保留；AI(tool_calls)+后续 tool 结果必须成组，整组装不下整组折叠
3. 折叠 ≥ 2 条时生成（或复用会话缓存）**滚动累积摘要**：以上一轮累积摘要 +
   本轮新折叠段为输入，由 LLM 重写为连续摘要（含过往要点与最近进展），落库覆盖
4. 系统提示词（职责/记忆/技能）由 multi-agent 各节点自行注入，不在此组装
"""

import logging

from app.config import settings
from app.core.llm_override import get_llm_override
from app.core.metrics import llm_calls, llm_latency, now
from app.prompt.prompts import SUMMARIZE_PROMPT

logger = logging.getLogger(__name__)

OUTPUT_RESERVE_BIG = 8192
SMALL_WINDOW = 32_000
SUMMARY_MAX_CHARS = 20_000


def estimate_tokens(text: str) -> int:
    """粗略估算：ASCII 每 4 字符 1 token，非 ASCII 每字符约 1 token，加 4 结构开销。

    超长文本（>4096 字符）改为前后抽样 2048 字符按比例估算，避免 O(n) 全量扫描阻塞
    事件循环（IMPROVEMENTS.md A9）。
    """
    t = text if isinstance(text, str) else str(text)
    if len(t) > 4096:
        sample = t[:2048] + t[-2048:]
        n = len(sample)
        asc = sum(1 for ch in sample if ord(ch) < 128)
        avg = (asc / 4 + (n - asc)) / n
        return max(4, int(len(t) * avg) + 4)
    asc = sum(1 for ch in t if ord(ch) < 128)
    return (len(t) - asc) + asc // 4 + 4


def _budget_tokens(window: int) -> int:
    """输入预算 = 窗口 - 输出余量；小窗口(<32K) 按 0.6 倍数。"""
    if window >= SMALL_WINDOW:
        return max(1, window - OUTPUT_RESERVE_BIG)
    return max(1, int(window * 0.6))


class _Group:
    __slots__ = ("msgs", "tokens")

    def __init__(self, msgs: list[dict]):
        self.msgs = msgs
        self.tokens = sum(estimate_tokens(m.get("content", "")) for m in msgs)

    @property
    def last_msg_id(self) -> str | None:
        """组内最后一条消息的稳定 msg_id（折叠键候选；未落库消息可能缺失）。"""
        return self.msgs[-1].get("id") or None


def _group_history(history: list[dict]) -> list[_Group]:
    """自尾向头分组，再还原时间顺序。工具链（ai tool_calls + 后续 tool 结果）不可拆分。"""
    groups: list[_Group] = []
    i = len(history) - 1
    while i >= 0:
        m = history[i]
        if m.get("role") == "tool":
            j = i
            while j >= 0 and history[j].get("role") == "tool":
                j -= 1
            if (
                j >= 0
                and history[j].get("role") == "ai"
                and history[j].get("tool_calls")
            ):
                groups.append(_Group(history[j : i + 1]))
                i = j - 1
            else:
                groups.append(
                    _Group(history[i : i + 1])
                )  # 游离 tool：不合法但独立成组兜底
                i -= 1
        else:
            groups.append(_Group(history[i : i + 1]))
            i -= 1
    groups.reverse()
    return groups


class BudgetResult:
    __slots__ = (
        "dropped",
        "estimated_tokens",
        "messages",
        "summary_generated",
        "summary_text",
        "summary_usage",
        "summary_used",
        "truncated",
    )

    def __init__(
        self,
        messages,
        dropped,
        truncated,
        summary_used,
        summary_generated,
        estimated_tokens,
        summary_usage,
        summary_text="",
    ):
        self.messages = messages
        self.dropped = dropped
        self.truncated = truncated
        self.summary_used = summary_used
        self.summary_generated = summary_generated
        self.estimated_tokens = estimated_tokens
        self.summary_usage = summary_usage
        self.summary_text = summary_text

    def to_meta(self) -> dict:
        return {
            "truncated": self.truncated,
            "summary_used": self.summary_used,
            "dropped_messages": self.dropped,
            **({"summary_text": self.summary_text} if self.summary_text else {}),
        }


async def _generate_summary(prev_summary: str, msgs: list[dict]) -> tuple[str, dict] | None:
    """用当前 override LLM 生成「重写式」累积摘要（过往摘要 + 本轮新折叠段）。

    返回 (摘要文本, usage)；失败 None。"""
    try:
        from app.core.llm import build_llm, current_model, current_provider

        ov = get_llm_override()
        llm = build_llm(
            provider=current_provider(),
            model=current_model(),
            api_key=(ov.api_key if ov and ov.api_key else None) or settings.llm_api_key,
            base_url=(ov.base_url if ov and ov.base_url else None)
            or settings.llm_base_url,
            timeout=60,
            tools=None,
        )
        transcript = "\n".join(
            f"{m.get('role')}: {str(m.get('content', ''))[:SUMMARY_MAX_CHARS]}"
            for m in msgs
        )
        labels = {"model": current_model()}
        start = now()
        try:
            result = await llm.ainvoke(
                SUMMARIZE_PROMPT.format(
                    prev_summary=prev_summary or "（无）",
                    transcript=transcript[: SUMMARY_MAX_CHARS * 2],
                )
            )
            llm_calls.inc(1, {**labels, "status": "success"})
        except Exception:
            llm_calls.inc(1, {**labels, "status": "error"})
            raise
        finally:
            llm_latency.observe(now() - start, labels)
        um = getattr(result, "usage_metadata", None) or {}
        return str(result.content).strip(), {
            "input_tokens": int(um.get("input_tokens") or 0),
            "output_tokens": int(um.get("output_tokens") or 0),
        }
    except Exception:
        logger.exception("summary generation failed, dropping early history")
        return None


async def build_context(
    history: list[dict],
    human_text: str,
    window: int,
    session_id: str | None = None,
) -> BudgetResult:
    """组装进入上下文的消息（dict 列表，调用方再转 BaseMessage）。

    multi-agent 后系统提示词（职责 + 记忆 + 技能指令）由各 worker/supervisor
    节点自行注入，此处只负责历史折叠：
    顺序：[摘要 System] → 保留历史 → Human(新消息)。
    """
    budget = _budget_tokens(window)
    groups = _group_history(history)

    used = estimate_tokens(human_text)

    kept: list[_Group] = []
    dropped_groups: list[_Group] = []
    # 从最新往回贪心保留（新消息优先）：
    # 1) 最近 context_keep_turns 组优先保留（近期上下文完整 + 前缀稳定利于缓存）
    # 2) 但同样受预算约束（A20：无条件保留可越预算）；装不下的折叠进摘要
    keep_turns = settings.context_keep_turns if hasattr(settings, "context_keep_turns") else 0
    recent = 0
    for g in reversed(groups):
        if keep_turns > 0 and recent < keep_turns and used + g.tokens <= budget:
            kept.append(g)
            used += g.tokens
            recent += 1
            continue
        if used + g.tokens <= budget:
            kept.append(g)
            used += g.tokens
        else:
            dropped_groups.append(g)
    kept.reverse()  # 还原时间顺序

    dropped_msgs = [m for g in dropped_groups for m in g.msgs]
    truncated = bool(dropped_msgs)

    summary_usage: dict | None = None
    summary_used = False
    summary_generated = False
    summary_text = ""

    if dropped_msgs:
        # 折叠键 = 被折叠区最新一条消息的稳定 msg_id（精确匹配）：
        # 折叠点未前移 → 缓存命中复用；前移 → 以「上轮摘要 + 本轮新折叠段」重写。
        # 不能用 rowid：replace_messages 每轮 DELETE+INSERT 使其单调漂移（A3）。
        fold_key = dropped_groups[0].last_msg_id
        cached = None
        if session_id and fold_key:
            from app.service import session_service

            try:
                cached = await session_service.get_session_summary(session_id)
            except Exception:  # noqa: BLE001 - 缓存读失败降级为无摘要
                cached = None
        # 缓存折叠键与当前一致 → 复用；否则以上一轮累积摘要 + 本轮新折叠段重写生成
        if cached is not None and cached[1] == fold_key and cached[0]:
            summary_text = cached[0]
            summary_used = True
        else:
            try:
                gen = await _generate_summary(
                    prev_summary=cached[0] if cached else "", msgs=dropped_msgs
                )
            except Exception:
                logger.exception("summary generation raised, dropping early history")
                gen = None
            if gen is not None:
                summary_text, summary_usage = gen
                summary_used = True
                summary_generated = True
                if session_id and fold_key:
                    try:
                        await session_service.set_session_summary(
                            session_id, summary_text, fold_key
                        )
                    except Exception:
                        logger.exception("failed to persist summary cache")
        if not summary_text:
            summary_used = False

    messages: list[dict] = []
    if summary_used:
        messages.append(
            {
                "role": "system",
                "content": f"【对话摘要（含过往与最近进展）】{summary_text}",
            }
        )
        # A20：摘要计入 token 估算（estimated_tokens 反映实际入上下文的全部内容）
        used += estimate_tokens(f"【对话摘要（含过往与最近进展）】{summary_text}")
    for g in kept:
        messages.extend(g.msgs)
    messages.append({"role": "user", "content": human_text})

    return BudgetResult(
        messages=messages,
        dropped=len(dropped_msgs),
        truncated=truncated,
        summary_used=summary_used,
        summary_generated=summary_generated,
        estimated_tokens=used,
        summary_usage=summary_usage,
        summary_text=summary_text,
    )
