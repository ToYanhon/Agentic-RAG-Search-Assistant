"""Agent 服务配置：优先读取 .env 文件，环境变量可覆盖（docker-compose 注入优先）。"""

import os
from pathlib import Path

from dotenv import load_dotenv

_env_path = Path(__file__).resolve().parents[1] / ".env"
load_dotenv(_env_path)


class Settings:
    llm_provider: str = os.getenv("LLM_PROVIDER", "openai")
    llm_api_key: str = os.getenv("LLM_API_KEY", "")
    llm_base_url: str = os.getenv("LLM_BASE_URL", "https://api.deepseek.com")
    llm_model: str = os.getenv("LLM_MODEL", "deepseek-chat")
    # 采样温度：默认不传（None）→ 各供应商用自己默认值（部分网关只允许 temperature=1）；
    # 需全局固定时设置 LLM_TEMPERATURE（如 0.1）。
    _llm_temperature = os.getenv("LLM_TEMPERATURE")
    llm_temperature: float | None = float(_llm_temperature) if _llm_temperature else None
    embedding_model: str = os.getenv("EMBEDDING_MODEL", "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")

    # 语义搜索重排（cross-encoder 精排）：粗排候选数 / 精排后阈值门控
    rerank_model: str = os.getenv("RERANK_MODEL", "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1")
    rerank_candidates: int = int(os.getenv("RERANK_CANDIDATES", "20"))
    # 双门控：绝对下限 rerank_min_score，与相对比例 max_score*rerank_min_ratio 取较大者。
    # mmarco 中文分数偏低（相关 ~0.02-0.1、噪声 ~0.004），绝对阈值须放很低，比例门控兜底。
    rerank_min_score: float = float(os.getenv("RERANK_MIN_SCORE", "0.01"))
    rerank_min_ratio: float = float(os.getenv("RERANK_MIN_RATIO", "0.1"))

    # 混合检索：稠密向量负责语义召回，稀疏向量补充专有名词和精确匹配。
    hybrid_search: bool = os.getenv("HYBRID_SEARCH", "true").lower() in {
        "1",
        "true",
        "yes",
        "on",
    }
    sparse_top_k: int = int(os.getenv("SPARSE_TOP_K", "20"))
    rrf_k: int = int(os.getenv("RRF_K", "60"))

    # 成本估算单价（元/百万 tokens），按官方定价配置
    llm_price_input: float = float(os.getenv("LLM_PRICE_INPUT", "1.0"))
    llm_price_output: float = float(os.getenv("LLM_PRICE_OUTPUT", "2.0"))

    # 长会话上下文：始终保留最近 N 轮完整消息（0 = 纯 token 预算模式），更早消息折叠进摘要。
    # 目的：近期上下文完整 + 前缀稳定以命中 provider 上下文缓存，同时限制历史无限膨胀。
    context_keep_turns: int = int(os.getenv("CONTEXT_KEEP_TURNS", "10"))

    # 后端地址（本地开发 localhost；容器部署用 http://backend:8080）
    backend_url: str = os.getenv("BACKEND_URL", "http://localhost:8080/api/v1")

    redis_url: str = os.getenv("REDIS_URL", "redis://localhost:6379/0")

    # Qdrant 向量库（语义索引持久化，替代 Redis kb:*）
    qdrant_url: str = os.getenv("QDRANT_URL", "http://localhost:6333")

    # SQLite 主存储位置（会话/消息持久化，Redis 降级为缓存）
    db_path: str = os.getenv("DB_PATH", str(Path(__file__).resolve().parents[1] / "data" / "sessions.db"))

    # 技能体系：全局技能目录（随部署分发，admin 可信，可带 tools.py）
    skills_dir: str = os.getenv(
        "SKILLS_DIR", str(Path(__file__).resolve().parents[1] / "skills")
    )
    # 全局技能热加载扫描间隔（秒）
    skills_scan_interval_sec: int = int(os.getenv("SKILLS_SCAN_INTERVAL_SEC", "30"))
    # 云盘技能：用户网盘根目录下承载技能包(.md)的文件夹名，纯指令式（永不执行用户代码）
    user_skills_folder: str = os.getenv("USER_SKILLS_FOLDER", "skills")
    # 用户技能缓存 TTL（秒），聊天时懒刷新
    user_skills_ttl_sec: int = int(os.getenv("USER_SKILLS_TTL_SEC", "300"))
    # 单个技能文件/指令大小上限（字节），防 prompt 膨胀与滥用
    skill_max_bytes: int = int(os.getenv("SKILL_MAX_BYTES", str(16 * 1024)))
    # 每轮注入的技能指令总预算（字节）
    skill_context_max_bytes: int = int(os.getenv("SKILL_CONTEXT_MAX_BYTES", str(8 * 1024)))


settings = Settings()
