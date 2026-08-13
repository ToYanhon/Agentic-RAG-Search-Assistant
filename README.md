# Agentic-RAG-Search-Assistant · 让网盘变得"能问"

三服务 AI 云盘：Java 主服务负责认证、对象存储与请求代理，Python Agent 负责工具编排与文件检索，React 提供网盘与流式对话界面。核心能力是 Agent 编排与混合检索 RAG——文件不只是存起来，还能被检索、被阅读、被回答。

## 特性与设计取舍

- **Agent 编排（无 LangGraph 依赖）**：Supervisor 按能力域路由到 file / web / general 三个 worker，执行 ReAct 多轮工具调用；工具名与 schema 由注册表运行时注入提示词，避免提示词与实现漂移。不引入 LangGraph 是为了减少对框架状态机的依赖，让回合控制流（单 async generator）和失败处理完全可读。
- **混合检索 RAG**：本地 embedding 稠密向量 + jieba BM25 风格稀疏向量双路召回，RRF 按名次融合，再经 Cross-Encoder 精排与双门控过滤低相关结果。两路分数含义与尺度不同，直接加权会互相干扰，RRF 只看相对名次所以天然免疫尺度问题。
- **多服务边界与安全**：JWT + 内部 agent token 轮换双认证；`AuthFilter` 区分调用方角色（JWT→user / X-Agent-Token→agent），写内容接口仅 agent 可用；用户 LLM 密钥 AES-GCM 加密落库，按请求解密后注入，前端不落明文；Java 代理层做 SSE 流式透传与并发限流。把业务域和 Agent 拆成两个服务，是为了让认证、存储与模型编排独立演进，敏感配置不需要下沉到 Agent。
- **检索一致性**：Qdrant 以 `user_id` payload 隔离多租户；文件删除与内容覆盖写由后端异步通知 Agent 维护向量索引——通知走 Redis 队列（`task:index_notify`），轮询消费 + 指数退避重试，Redis 不可用回退直发，避免已删/已改内容继续被检索。
- **可验证**：后端 JUnit、Agent pytest 全部离线可跑；自建中文检索评估集（精确匹配 / 语义改写 / 跨段三类），按 Recall@k / MRR 对比分块、精排与混合检索方案（【实测数据待补充】）。
- **多格式索引**：txt / PDF / docx 原生解析，xlsx / xls / pptx / ppt / csv / html 与 jpg / jpeg / png 图片经 MarkItDown 转 Markdown 文本进入同一向量链路；图片可选用用户配置的视觉 LLM 生成中文描述（OpenAI 兼容 provider），无视觉能力时自动跳过。
- **Agent 工具**：file / web / general 三组（工具名与 schema 由注册表运行时注入，防漂移）。读文件支持按行 offset/limit + 非文本 MarkItDown 回退；写/编辑（`write_file_content` / `edit_file_content`）owner 限定、走 agent-only 接口。
- **后台任务骨架**：进程内「尽力而为」任务统一重试语义（`bg_tasks`：`run_bg` / `await_with_retry`），记忆提炼与消息持久化失败可重试。

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
mvn test                       # JUnit + Mockito 纯单测，无需基础设施
```

```bash
# agent/
copy .env.example .env         # 填 LLM 密钥 / REDIS_URL / BACKEND_URL（QDRANT_URL 缺省 localhost:6333）
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

- 索引维护通知（删除/覆盖写）走 Redis 队列 + 指数退避重试，但 agent 彻底不可达且重试超限时通知仍会被丢弃（尽力而为）；强一致需要 tombstone 或删除状态，尚未实现。
- 检索评估集的数字尚未写入文档，待实测后补充。
- 暂无 Query 改写（HyDE / step-back）与原生多模态向量（CLIP）索引；图像/Office 等通过 MarkItDown 文本化进入现有向量链路，多模态"理解"依赖用户配置的视觉 LLM，非视觉 provider 时图片索引被跳过。扫描版 PDF（无文本层）暂不支持 OCR。

## 文档

- `AGENTS.md` — 开发约定、命令与避坑（改代码前先读）。
- `backend/README.md` — 后端迁移说明与进度。
