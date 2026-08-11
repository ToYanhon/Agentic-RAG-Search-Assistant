# CloudDrive AI · 让网盘变得"能问"

三服务 AI 云盘：Java 主服务负责认证、对象存储与请求代理，Python Agent 负责工具编排与文件检索，React 提供网盘与流式对话界面。核心能力是 Agent 编排与混合检索 RAG——文件不只是存起来，还能被检索、被阅读、被回答。

## 特性与设计取舍

- **Agent 编排（无 LangGraph 依赖）**：Supervisor 按能力域路由到 file / web / general 三个 worker，执行 ReAct 多轮工具调用；工具名与 schema 由注册表运行时注入提示词，避免提示词与实现漂移。不引入 LangGraph 是为了减少对框架状态机的依赖，让回合控制流（单 async generator）和失败处理完全可读。
- **混合检索 RAG**：本地 embedding 稠密向量 + jieba BM25 风格稀疏向量双路召回，RRF 按名次融合，再经 Cross-Encoder 精排与双门控过滤低相关结果。两路分数含义与尺度不同，直接加权会互相干扰，RRF 只看相对名次所以天然免疫尺度问题。
- **多服务边界与安全**：JWT + 内部 agent token 轮换双认证；用户 LLM 密钥 AES-GCM 加密落库，按请求解密后注入，前端不落明文；Java 代理层做 SSE 流式透传与并发限流。把业务域和 Agent 拆成两个服务，是为了让认证、存储与模型编排独立演进，敏感配置不需要下沉到 Agent。
- **检索一致性**：Qdrant 以 `user_id` payload 隔离多租户；文件删除由后端异步通知 Agent 级联取消索引，避免已删内容继续被检索。
- **可验证**：后端 JUnit、Agent pytest 全部离线可跑；自建中文检索评估集（精确匹配 / 语义改写 / 跨段三类），按 Recall@k / MRR 对比分块、精排与混合检索方案（【实测数据待补充】）。

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

## 快速开始

```bash
docker compose up -d mysql redis minio qdrant
```

```bash
# backend/
mvn spring-boot:run            # dev，默认 127.0.0.1:8080
mvn test                       # JUnit，H2 内存库，无需基础设施
```

```bash
# agent/
copy .env.example .env         # 填 LLM 密钥 / REDIS_URL / BACKEND_URL / QDRANT_URL
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
pytest test/                   # 全部 mock，无需基础设施
```

```bash
# frontend/
npm install
npm run dev                    # 127.0.0.1:5173，/api 代理到 8080
```

## 测试与评估

```bash
python -u test/eval_rag.py --top-k 5     # agent/ 下，需 Qdrant；默认只测召回阶段
python -u test/eval_rag.py --with-rerank # 加跑 Cross-Encoder 终排，耗时更长
```

## 已知边界 / 后续计划

- 删除后的索引清理是异步尽力而为，删除接口成功不代表 Qdrant 已清理；强一致需要 tombstone 或删除状态，尚未实现。
- 检索评估集的数字尚未写入文档，待实测后补充。
- 暂无 Query 改写（HyDE / step-back）与多模态索引，扩展点在文本提取注册表中已预留。

## 文档

- `AGENTS.md` — 开发约定、命令与避坑（改代码前先读）。
- `backend/README.md` — 后端迁移说明与进度。
