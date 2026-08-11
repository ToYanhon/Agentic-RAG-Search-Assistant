"""RAG 离线评估：对比旧分块、优化分块和混合检索。

运行：
    python test/eval_rag.py

需要本地 Qdrant 和 sentence-transformers 模型；不调用 LLM。
"""

import argparse
import asyncio
import json
from pathlib import Path
from statistics import mean
import sys


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.config import settings
from app.core.embedding import _rrf, searcher
from app.core.rag import RAGEngine
from app.core.sparse import build_sparse_vector
from app.core.vector_store import vector_store


CORPUS_PATH = ROOT / "test" / "fixtures" / "eval_corpus.json"
DEFAULT_OUTPUT = ROOT / "data" / "eval_results.json"
EVAL_USER_ID = 990001


def legacy_chunk_text(text: str, chunk_size: int = 500) -> list[str]:
    """复现优化前的固定长度分块，作为 baseline。"""
    return [text[i : i + chunk_size] for i in range(0, len(text), chunk_size)]


def _load_corpus() -> tuple[list[dict], list[dict]]:
    with CORPUS_PATH.open("r", encoding="utf-8") as handle:
        data = json.load(handle)
    return data["documents"], data["queries"]


async def _index_documents(documents: list[dict], optimized_chunking: bool) -> None:
    rag = RAGEngine()
    prepared: list[tuple[dict, list[str]]] = []
    all_chunks: list[str] = []
    for document in documents:
        chunks = (
            rag.chunk_text(document["text"])
            if optimized_chunking
            else legacy_chunk_text(document["text"])
        )
        prepared.append((document, chunks))
        all_chunks.extend(chunks)

    embeddings = await searcher.embed_texts(all_chunks)
    offset = 0
    for document, chunks in prepared:
        count = len(chunks)
        await vector_store.upsert_chunks(
            document["id"],
            EVAL_USER_ID,
            chunks,
            embeddings[offset : offset + count],
            chunk_type="eval",
            sparse_vectors=[build_sparse_vector(chunk) for chunk in chunks],
        )
        offset += count


def _metrics(results: list[dict], relevant: set[int], top_k: int) -> dict[str, float]:
    ranked_ids: list[int] = []
    seen: set[int] = set()
    for result in results:
        file_id = result.get("file_id")
        if file_id is None or file_id in seen:
            continue
        seen.add(file_id)
        ranked_ids.append(file_id)
        if len(ranked_ids) >= top_k:
            break
    hits = [file_id for file_id in ranked_ids if file_id in relevant]
    return {
        "recall_at_k": len(set(hits)) / len(relevant) if relevant else 0.0,
        "hit_at_1": 1.0 if ranked_ids and ranked_ids[0] in relevant else 0.0,
        "mrr": next(
            (1.0 / rank for rank, file_id in enumerate(ranked_ids, start=1) if file_id in relevant),
            0.0,
        ),
    }


async def _retrieve_batch(
    queries: list[dict], top_k: int, hybrid: bool
) -> list[list[dict]]:
    """批量生成 query embedding，并发执行本地 Qdrant 召回。"""
    if not queries:
        return []
    embeddings = await searcher.embed_texts([query["query"] for query in queries])
    candidates = max(settings.rerank_candidates, top_k)
    semaphore = asyncio.Semaphore(8)

    async def retrieve(index: int) -> list[dict]:
        async with semaphore:
            if not hybrid:
                dense = await vector_store.search(embeddings[index], EVAL_USER_ID, candidates)
                return dense[:candidates]
            dense, sparse = await asyncio.gather(
                vector_store.search(embeddings[index], EVAL_USER_ID, candidates),
                vector_store.search_sparse(
                    build_sparse_vector(queries[index]["query"]),
                    EVAL_USER_ID,
                    max(settings.sparse_top_k, candidates),
                ),
            )
            return _rrf([dense, sparse], settings.rrf_k, candidates)

    return list(await asyncio.gather(*(retrieve(index) for index in range(len(queries)))))


async def _evaluate(queries: list[dict], top_k: int, hybrid: bool, with_rerank: bool) -> dict:
    candidate_sets = await _retrieve_batch(queries, top_k, hybrid)
    final_rows = []
    retrieval_rows = []
    for query, candidates in zip(queries, candidate_sets):
        relevant = set(query["relevant_files"])
        retrieval_scores = _metrics(candidates, relevant, top_k)
        base = {"id": query["id"], "query": query["query"]}
        retrieval_rows.append({**base, **retrieval_scores})
        if with_rerank:
            final = await searcher.rank_candidates(query["query"], candidates, top_k)
            final_scores = _metrics(final, relevant, top_k)
            final_rows.append({**base, **final_scores})

    def aggregate(rows: list[dict]) -> dict:
        return {
            "query_count": len(rows),
            "recall_at_k": mean(row["recall_at_k"] for row in rows) if rows else 0.0,
            "hit_at_1": mean(row["hit_at_1"] for row in rows) if rows else 0.0,
            "mrr": mean(row["mrr"] for row in rows) if rows else 0.0,
            "queries": rows,
        }

    result = {"retrieval": aggregate(retrieval_rows)}
    if with_rerank:
        result["final"] = aggregate(final_rows)
    return result


async def _run(args) -> dict:
    documents, queries = _load_corpus()
    if args.query_limit > 0:
        queries = queries[: args.query_limit]
    scenarios = [
        ("baseline_dense_fixed_chunk", False, False),
        ("dense_sentence_chunk", False, True),
        ("hybrid_sentence_chunk", True, True),
    ]
    results = {}

    for name, hybrid, optimized_chunking in scenarios:
        await vector_store.recreate_collection()
        settings.hybrid_search = hybrid
        await _index_documents(documents, optimized_chunking)
        scenario = await _evaluate(queries, args.top_k, hybrid, args.with_rerank)
        retrieval = scenario["retrieval"]
        results[name] = scenario
        message = (
            f"{name}: retrieval recall@{args.top_k}={retrieval['recall_at_k']:.4f} "
            f"retrieval MRR={retrieval['mrr']:.4f}"
        )
        if args.with_rerank:
            final = scenario["final"]
            message += (
                f"; final recall@{args.top_k}={final['recall_at_k']:.4f}"
                f" final MRR={final['mrr']:.4f}"
            )
        print(message, flush=True)

    if not args.keep:
        await vector_store.delete_user(EVAL_USER_ID)
    settings.hybrid_search = True
    return {
        "top_k": args.top_k,
        "corpus": str(CORPUS_PATH),
        "scenarios": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--query-limit", type=int, default=0, help="仅评估前 N 条 query，调试用")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--keep", action="store_true", help="保留评估用户的索引以便调试")
    parser.add_argument("--with-rerank", action="store_true", help="额外评估 Cross-Encoder 终排，耗时较长")
    args = parser.parse_args()
    result = asyncio.run(_run(args))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"结果已写入 {args.output}", flush=True)


if __name__ == "__main__":
    main()
