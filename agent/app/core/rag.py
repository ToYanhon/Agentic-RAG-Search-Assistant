"""RAG（检索增强生成）引擎，支持文件文本提取、分段和 LLM 摘要。

摘要调用遵循请求级 LLM 覆盖（app.core.llm_override 的 contextvar）：
前端 AI 配置齐全时长 chat 调 summarize_file 工具 / 直连 /summary 路由，
都使用用户自己的 Provider / Base URL / API Key / 模型，而非 env 默认。
"""

import asyncio
import io
import logging
import re
from pathlib import Path

import httpx
from langchain_core.messages import HumanMessage, SystemMessage

from app.config import settings
from app.core.llm import build_llm
from app.core.llm_override import LLMOverride, get_llm_override
from app.prompt.prompts import DOC_SUMMARY_PROMPT

logger = logging.getLogger(__name__)

BACKEND_URL = settings.backend_url
SUMMARY_RETRIES = 3


def _extract_txt(content: bytes) -> str:
    return content.decode("utf-8", errors="ignore")


def _extract_pdf(content: bytes) -> str:
    import fitz

    doc = fitz.open(stream=content, filetype="pdf")
    return "\n".join(str(doc.load_page(i).get_text()) for i in range(len(doc)))


def _extract_docx(content: bytes) -> str:
    import docx

    doc = docx.Document(io.BytesIO(content))
    return "\n".join(p.text for p in doc.paragraphs)


# 文本提取器注册表：扩展名(lower) → (语义族 type, 提取器或 None)。
# 语义族用于索引 payload 的 type 标注（检索不受影响）；提取器为空表示该类型已预留但暂未接入。
# 未来新增类型（图片 OCR / xlsx / pptx 等）只需在此注册提取器，即可无缝进入语义索引链路。
_EXTRACTORS: dict[str, tuple[str, object]] = {
    ".docx": ("docx", _extract_docx),
    ".pdf": ("pdf", _extract_pdf),
    ".txt": ("text", _extract_txt),
    # ---- 预留（族已就位，提取器待接入）----
    ".md": ("text", None),
    ".markdown": ("text", None),
    ".csv": ("text", None),
    ".html": ("text", None),
    ".xlsx": ("xlsx", None),
    ".xls": ("xlsx", None),
    ".pptx": ("pptx", None),
    ".ppt": ("pptx", None),
    ".png": ("image", None),
    ".jpg": ("image", None),
    ".jpeg": ("image", None),
    ".gif": ("image", None),
    ".webp": ("image", None),
    ".bmp": ("image", None),
}


def _extract_key(filename: str) -> str:
    return Path(filename).suffix.lower()


def detect_kind(filename: str) -> str:
    """返回文件名对应的语义族（payload type 标引用；未知格式返回 unknown）。"""
    entry = _EXTRACTORS.get(_extract_key(filename))
    return entry[0] if entry else "unknown"


class RAGEngine:
    """按需构建 LLM；每次摘要单独拿覆盖，避免跨请求串配置。"""

    @staticmethod
    def _llm_for(ov: LLMOverride | None):
        """按请求覆盖（provider/base_url/api_key/model）构建无工具 LLM Runnable。"""
        return build_llm(
            provider=(ov.provider if ov and ov.provider else None) or settings.llm_provider,
            model=(ov.model if ov and ov.model else None) or settings.llm_model,
            api_key=(ov.api_key if ov and ov.api_key else None) or settings.llm_api_key,
            base_url=(ov.base_url if ov and ov.base_url else None) or settings.llm_base_url,
            timeout=60,
        )

    async def download(self, file_id: int, user_id: int) -> bytes | None:
        from app.auth_token import get_internal_token
        token = await get_internal_token()
        async with httpx.AsyncClient(headers={"X-Agent-Token": token}, timeout=15.0, trust_env=False) as client:
            resp = await client.get(f"{BACKEND_URL}/files/{file_id}/download", params={"user_id": user_id})
            if resp.status_code == 401:
                # 内部 token 可能已轮换：重置缓存取最新 token 重试一次
                token = await get_internal_token(force_refresh=True)
                async with httpx.AsyncClient(headers={"X-Agent-Token": token}, timeout=15.0, trust_env=False) as client2:
                    resp = await client2.get(f"{BACKEND_URL}/files/{file_id}/download", params={"user_id": user_id})
            return resp.content if resp.status_code == 200 else None

    async def _filename(self, file_id: int, user_id: int) -> str:
        """查询文件真实名称，决定文本解析类型（txt/pdf/docx）。失败返回空串（调用方兜底 txt）。"""
        from app.auth_token import get_internal_token
        token = await get_internal_token()
        try:
            async with httpx.AsyncClient(headers={"X-Agent-Token": token}, timeout=10.0, trust_env=False) as client:
                resp = await client.get(f"{BACKEND_URL}/files/{file_id}", params={"user_id": user_id})
                if resp.status_code == 200:
                    return (resp.json().get("data") or {}).get("name", "")
        except (httpx.HTTPError, ValueError):
            pass
        return ""

    def extract_text(self, content: bytes, filename: str) -> str:
        """按扩展名从注册表分派提取；未注册/提取器为 None 返回空串（沿用原行为）。"""
        ext = Path(filename).suffix.lower()
        instance = _EXTRACTORS.get(ext)
        if not instance or instance[1] is None:
            return ""
        try:
            return instance[1](content)
        except Exception:
            logger.exception("text extraction failed for %s", filename)
            return ""

    def chunk_text(self, text: str, chunk_size: int = 500, overlap: int = 80) -> list[str]:
        """按句子合并文本，超长句再硬切，并保留少量上下文重叠。"""
        if not text or chunk_size <= 0:
            return []
        overlap = max(0, min(overlap, chunk_size // 2))
        units = [part.strip() for part in re.findall(r".+?(?:[。！？；!?;\n]+|$)", text, re.S) if part.strip()]
        if not units:
            return []

        # 先按较小窗口合并，再在相邻窗口之间补 overlap，避免末尾产生孤立重叠块。
        base_size = max(1, chunk_size - overlap)
        base_chunks: list[str] = []
        current = ""

        def emit_base(value: str) -> None:
            value = value.strip()
            if value:
                base_chunks.append(value)

        for unit in units:
            if len(unit) > base_size:
                if current:
                    emit_base(current)
                    current = ""
                for start in range(0, len(unit), base_size):
                    emit_base(unit[start : start + base_size])
                continue

            if not current:
                current = unit
                continue
            if len(current) + len(unit) <= base_size:
                current += unit
                continue

            emit_base(current)
            current = unit

        emit_base(current)
        chunks = [base_chunks[0]]
        for previous, current in zip(base_chunks, base_chunks[1:]):
            prefix = previous[-overlap:] if overlap else ""
            chunks.append(prefix + current)
        return chunks

    async def summarize(self, file_id: int, user_id: int) -> str | None:
        content = await self.download(file_id, user_id)
        if content is None:
            return None

        # 用真实文件名解析文本（后缀决定 txt/pdf/docx 解析方式）
        filename = await self._filename(file_id, user_id) or f"file_{file_id}.txt"
        text = self.extract_text(content, filename)
        if not text.strip():
            return None

        # 请求级 LLM 覆盖：用户配置的 Provider / Base URL / API Key / 模型优先于 env 默认
        ov = get_llm_override()
        llm = self._llm_for(ov)

        truncated = text[:3000]
        last_exc: Exception | None = None
        for attempt in range(SUMMARY_RETRIES):
            try:
                resp = await llm.ainvoke(
                    [
                        SystemMessage(content=DOC_SUMMARY_PROMPT),
                        HumanMessage(content=f"文件内容：\n{truncated}"),
                    ],
                    max_tokens=500,
                )
                return (resp.content if isinstance(resp.content, str) else "") or "（摘要生成失败）"
            except Exception as e:  # noqa: BLE001
                last_exc = e
                if attempt < SUMMARY_RETRIES - 1:
                    await asyncio.sleep(min(2**attempt, 8))
        logger.warning("LLM 摘要失败: %s", last_exc)
        return "（摘要生成失败，请稍后重试）"
