"""语义搜索重排器 — 本地 cross-encoder 精排（Qdrant ANN 粗排之后的第二阶段）。

模型: cross-encoder/mmarco-mMiniLMv2-L12-H384-v1（多语言，支持中文）
复用 sentence-transformers 内置 CrossEncoder，零新增依赖，本地离线运行。
predict 输出 logits，经 sigmoid 归一化为 [0,1] 概率供阈值门控。
"""

import asyncio
import logging
import math

from app.config import settings

logger = logging.getLogger(__name__)


def _sigmoid(x: float) -> float:
    if x >= 0:
        z = math.exp(-x)
        return 1.0 / (1.0 + z)
    z = math.exp(x)
    return z / (1.0 + z)


class Reranker:
    def __init__(self):
        self._model = None
        self._model_lock = asyncio.Lock()

    async def _load_model(self):
        if self._model is None:
            async with self._model_lock:
                if self._model is None:
                    from sentence_transformers import CrossEncoder

                    self._model = CrossEncoder(settings.rerank_model)
        return self._model

    def _predict_sync(self, pairs: list[list[str]]) -> list[float]:
        assert self._model is not None
        return [float(s) for s in self._model.predict(pairs)]

    async def rerank(self, query: str, candidates: list[dict], top_k: int) -> list[dict]:
        """对候选片段精排：按 cross-encoder 分数降序，截取 top_k。

        无候选或 top_k<=0 时直接返回空；模型加载失败抛异常（由调用方降级）。
        """
        if not candidates or top_k <= 0:
            return []
        await self._load_model()
        pairs = [[query, c.get("chunk") or ""] for c in candidates]
        loop = asyncio.get_running_loop()
        logits = await loop.run_in_executor(None, self._predict_sync, pairs)
        ranked = sorted(
            zip(candidates, logits),
            key=lambda cs: cs[1],
            reverse=True,
        )
        out = []
        for c, s in ranked[:top_k]:
            item = dict(c)
            item["score"] = _sigmoid(s)
            out.append(item)
        return out


reranker = Reranker()
