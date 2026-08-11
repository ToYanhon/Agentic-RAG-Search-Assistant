# CloudDrive AI · Agentic RAG 文件智能检索系统

AI 云盘：三服务架构的智能文件检索问答系统。Java 主服务负责认证、对象存储与请求代理，Python Agent 负责工具编排与文件检索，React 提供网盘界面与流式对话。

## 架构

```
frontend (React, :5173 dev / :3000 nginx)
    │  /api/v1 (JWT Bearer)
    ▼
backend  (Java 21 / Spring Boot 3.3, :8080)
    │  SSE 流式代理（注入 X-User-Id / X-LLM-* / X-Agent-Token）
    ▼
agent    (Python 3.12 / FastAPI, :8000，仅绑定 127.0.0.1)
    │  内部接口回访后端文件服务
    ▼
MySQL（元数据）· Redis（缓存/认证/记忆）· MinIO（文件对象）· Qdrant（检索索引）
```

- `backend/` — Spring Boot：认证（JWT + agent token 轮换双认证）、文件域（秒传 / 分块上传 / 文件夹 / 分享）、LLM 配置（AES-GCM 加密）、Agent 代理层（SSE 透传 + 限流）。
- `agent/` — FastAPI：自研 Agent 编排框架（无 LangGraph 依赖），Supervisor→Worker（file/web/general）ReAct 多轮工具调用；技能体系（全局可执行工具 + 用户只读指令）；混合 RAG 检索。
- `frontend/` — React 18 + TS + Vite：网盘管理、流式聊天 Copilot、公开分享页。

## 快速开始

基础设施：

```bash
docker compose up -d mysql redis minio qdrant
```

后端（`backend/`）：

```bash
mvn spring-boot:run        # dev，默认 127.0.0.1:8080
mvn test                   # JUnit，H2 内存库，无需基础设施
```

Agent（`agent/`）：

```bash
copy .env.example .env     # 填入 LLM 密钥 / REDIS_URL / BACKEND_URL / QDRANT_URL
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
pytest test/               # 全部 mock，无需基础设施
```

前端（`frontend/`）：

```bash
npm install
npm run dev                # 127.0.0.1:5173，/api 代理到 8080
```

## 配置

- `APP_MODE` 选择 dev / prod profile（dev 用 localhost，prod 用 docker 服务名）。
- 任意字段可用 `CD_<SECTION>_<FIELD>` 覆盖；prod 必须通过 `CD_LLM_ENCRYPTION_KEY` 提供 LLM 配置主密钥（≥32 字节）。

## RAG 检索链路

文件索引：提取文本（txt/pdf/docx）→ 句子分块（重叠窗口）→ 稠密向量（本地 embedding）+ 稀疏向量（jieba 词频）写入 Qdrant `kb`（`user_id` payload 隔离）。

查询链路：`semantic_search` 工具 → 稠密 + 稀疏双路召回 → RRF 融合 → Cross-Encoder 精排 → 双门控（绝对 + 相对阈值）→ Agent 决定继续阅读 / 摘要 / 回答。

一致性：文件删除由 Java 异步通知 Agent 级联取消索引，保证“已删即不可检索”。

离线评估：`python -u test/eval_rag.py --top-k 5`（需 Qdrant），按 Recall@k / MRR 对比分块、精排与混合检索方案；结果写入 `data/eval_results.json`（gitignored）。

## 文档

- `AGENTS.md` — 开发约定与避坑（新代码务必先读）。
- `backend/README.md` — 后端迁移说明与进度。
- `docs/`（gitignored）— 简历、面试拷问与答案、项目总结。
- `diagrams/`（gitignored）— 架构与 RAG 流程 Draw.io 图。
