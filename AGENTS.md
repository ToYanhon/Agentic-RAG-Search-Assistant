# AGENTS.md

Agentic-RAG-Search-Assistant = AI 云盘。三个服务由 `docker-compose.yml` 串联（backend → agent 代理转发，frontend → backend `/api/v1`）。代码注释与文档均为中文，新增内容请保持同风格。**Go 后端已删除，Java 是唯一后端**（backend 无 `.go` 文件）。注意：本目录 `.git` 已损坏、`git` 命令不可用，别依赖 git 历史。

## 目录结构
- `backend/` — Spring Boot 3.3 + Java 21 + Maven + MySQL/Redis/MinIO。入口 `com.clouddrive.CloudDriveApplication`（`src/main/java/com/clouddrive/`）。分层：`controller`（REST）→ `service` → `repository`（Spring Data JPA）→ `entity`；另有 `proxy/`（`AgentClient.java` 把全部 `/api/v1/agent/*` 代理给 Python agent，SSE 透传）、`storage/`（`MinioStorage.java`，AWS SDK v2 S3Client）、`security/`（JWT+双认证+agent token 轮换）、`common/`（Resp 信封/错误码）、`config/`（`AppProperties` + `CD_` 覆盖）。
- `agent/` — Python 3.12 + FastAPI + **Agent 编排框架（无 langgraph 依赖，只保留 langchain-openai/langchain-core）**。入口 `app/main.py`；路由在 `app/router/`（`chat`/`index`/`memory`/`summary`）。完整回合编排在 `app/agent/workflow.py` 的 `AgentWorkflow.turn()`（单 async generator：历史→记忆→技能→上下文折叠→supervisor 路由→worker ReAct 循环→持久化→后台记忆提炼→meta/done），工具/记忆/技能/上下文分别由 `app/agent/` 下的 `tool_manager.py` / `memory_manager.py` / `skill_manager.py` / `context_manager.py` 承担；worker 划分（`file`/`web`/`general`，记忆工具并入 general）见 `app/agent/workers.py`。**全部提示词统一在 `app/prompt/prompts.py`**（按域分节：`SUPERVISOR_SYSTEM_PROMPT`/`WORKER_PROMPTS`/`SUMMARIZE_PROMPT`/`DEDUP_PROMPT`/`SKILL_CONTEXT_GUARD` 等，`app/prompt/__init__.py` 聚合导出）。系统提示词统一四段式（角色设定/能力边界/回复规范/安全约束），**不在提示词里手写枚举工具**——工具名由 `build_worker_prompt`/`build_supervisor_prompt` 运行时从 `worker_tools()`/`WORKER_NAMES` 自动注入（防漂移），工具语义由 `bind_tools` schema 承担（转移工具描述含 `WORKER_TAGLINES`）。语义检索 = Qdrant `kb` collection 中的稠密+稀疏向量 RRF 融合，再经 Cross-Encoder 精排和双门控（`app/core/vector_store.py`/`embedding.py`），`user_id` payload 过滤隔离，记忆 = Redis + SQLite 双写，LLM 走 OpenAI 兼容接口。
- `agent/` 技能体系：全局技能 `agent/skills/` 支持两种形态——目录式 `<name>/SKILL.md`（可带 `tools.py`，其 `@tool`/`@safe_tool` 注册为可执行工具，admin 可信）+ 扁平式 `<name>.md`（纯指令式，无代码）；云盘技能 = 用户网盘根目录 `skills/` 里的 `.md`，纯指令式，**永不执行用户代码**。加载/触发/热更新见 `app/core/skills/`（`_template`、下划线前缀均为模板不加载）。
- `frontend/` — React 18 + TS + Vite + Tailwind。常规请求统一经 `src/api/`（`client.ts` 自动注入 `localStorage.token` 作为 Bearer；SSE 流式用裸 `fetch` 手写头）。Vite dev 代理 `/api` → `localhost:8080`；**公开分享 `/s/:token` 无 JWT，`api/shares.ts` 用 `VITE_BACKEND_ORIGIN`（默认 `http://localhost:8080`）直连后端绕过代理**。docker 部署由 nginx 服务（compose 端口 3000）。

## 命令（工作目录很重要）
- 基础设施：`docker compose up -d mysql redis minio qdrant`。本地 MinIO API 端口是 `localhost:9100`（9000 被映射走了），不是默认端口——`application-dev.yml` 已指向它。
- Backend（在 `backend/` 下）：`mvn spring-boot:run`（dev，默认 127.0.0.1:8080）；验证 `mvn test`（JUnit，H2 内存库，无需基础设施；单个用例 `mvn test -Dtest=FileServiceTest`）；打包 `mvn package` 后 `java -jar target/backend-0.1.0.jar` 启动。健康检查轮询 `/health`。
- OpenAPI（在 `backend/` 下）：springdoc 自动生成（无 swag），dev 下 `/swagger-ui` 可见。新增 agent 接口注册到 `controller/AgentController.java`，与 Python 侧 `agent/router/` 的路由一一对应。
- Agent（在 `agent/` 下）：先 `copy .env.example .env` 填 LLM 密钥/`REDIS_URL`/`BACKEND_URL`/`QDRANT_URL`（`config.py` 加载它，`BACKEND_URL` 以 `/api/v1` 结尾；LLM 密钥会被后端按请求注入的 `X-LLM-*` 头覆盖，env 只是 fallback，见 `core/llm_override.py` contextvar）。然后 `pip install -r requirements.txt`；运行 `uvicorn app.main:app --reload --port 8000`。单测：`pytest test/`（全部 mock，无需基础设施）。`test/test_memory.py`、`test/test_search.py`、`test/test_chain.py` 是脚本式（用 `python` 运行，不是 pytest）：test_memory 需真实 Redis（SQLite 走临时库）、test_search 需真实 Qdrant + 本机 embedding 模型、test_chain 需后端 8080 + agent + LLM 密钥的完整链路。RAG 评估：`python -u test/eval_rag.py --top-k 5` 默认只评估召回阶段；加 `--with-rerank` 会额外运行 Cross-Encoder 终排，耗时明显增加（需 Qdrant；结果写入 gitignored 的 `data/eval_results.json`）。agent 自身暴露 `/health`、`/metrics`。
- Frontend（在 `frontend/` 下）：`npm install`；`npm run dev`（127.0.0.1:5173）；`npm run build`（= `tsc -b && vite build`，唯一类型检查；无 linter）。

## 配置模型（backend）
`application.yml` + `application-<dev|prod>.yml`，`APP_MODE` 环境变量选 profile；任意字段可用 `CD_<SECTION>_<FIELD>` 覆盖（如 `CD_SERVER_PORT`、`CD_MINIO_USE_SSL`）。prod yaml 使用 docker 服务名（`mysql`、`redis`、`minio:9000`、`agent:8000`）。`llm.encryption-key`（用户 LLM 配置的 AES-GCM 主密钥，≥32 字节）在 prod 必须通过 `CD_LLM_ENCRYPTION_KEY` 提供。

## LLM 配置（按供应商、存于后端）
用户 AI 配置按供应商存在后端（`llm_configs` 表，密钥 AES-GCM 加密），经 `LLMConfigController` 的 `GET/PUT/DELETE /api/v1/llm-config[/:provider]` 访问（`service/LLMConfigService.java`）。前端只发 `X-LLM-Provider`；`proxy/AgentClient.java` 解密已存密钥并注入 `X-LLM-Base-URL/X-LLM-Key/X-LLM-Model`（provider 为 `tavily` 时加 `X-Tavily-Key`）再转发，agent 侧按请求读取（`chat.py`/`summary.py` 的 `llm_override`）。编辑某个 provider 不影响其他 provider；密钥长度无上限。每个 agent 接口必须在 `AgentController.java` 显式注册（后端代理层，非自动转发）。

## 后端（backend/）注意事项
- **工作约定（务必遵守）**：多步骤任务连续推进、不要停在中间等用户输入「继续」——一次性做完并在结尾汇总。启动 Java 后端验证时用**后台异步方式**（`Start-Process java -ArgumentList "-jar",... -RedirectStandardOutput ...` + 轮询 `/health`），不要阻塞等待；每个阶段结束汇报即可。
- 双认证：`Authorization` Bearer JWT + agent 内部 `X-Agent-Token`（`security/JwtService.java`、`security/AuthFilter.java`、`security/AgentTokenManager.java`，token 轮换走 Redis `internal:agent:token`，Python 侧 `app/auth_token.py` 读取同一 key）。
- agent 无入站鉴权：它信任后端代理注入的 `X-User-Id`。切勿直接暴露 agent:8000（docker-compose 仅绑定 `127.0.0.1`）。
- 文件删除（单个 / 文件夹级联）通过 `service/AgentNotifier.java`（`notifyUnindex`）异步通知 agent 从 Qdrant 取消索引（尽力而为）。改删除代码时务必保留该调用；否则已删文件仍会被 AI 检索到。
- 秒传为 owner 级限定（`FileRepository.findFirstByMd5AndOwnerId`）并把对象复制到新 key（`MinioStorage.copyObject`）；不要重新引入全局按 MD5 复用。
- 上传限制：直传 ≤ 50MB，分块 10MB，总分 10GB（`AppProperties.Upload` / `application-dev.yml`）。
- 避坑：① 所有请求/响应 DTO 都要 `@JsonNaming(SnakeCaseStrategy)`（契约是 snake_case JSON）；② 对象存储用 AWS SDK v2 S3Client（path-style 寻址，原生 S3 multipart），MinIO Java SDK 无 multipart API；③ jjwt 拒绝 <32B HS256 密钥，用 SHA-256 派生；④ **Java HttpClient 默认 HTTP/2 会对明文发 h2c upgrade，代理 agent 必须 `.version(HTTP_1_1)`**；⑤ 代理复制入站头要跳过 Java 受限头（expect/connection/host/upgrade/content-length）；⑥ `@JsonNaming` 的 `PREFIX="v1:"` 与 split 后 parts[0]="v1" 判等坑（AES-GCM 密文前缀校验用 VERSION 不带冒号）；⑦ RAG 的 `kb` 已是 `dense`/`sparse` 命名向量 schema，改 collection schema 后必须在 dev 删除重建并全量索引；⑧ 打包前须停掉正在运行的 jar（Windows 锁文件）；⑨ 用 `edit`/`write` 工具改文件，**禁用 PowerShell 的 `Set-Content`/`Get-Content -Raw -replace` 改含中文的源文件**（会损坏 UTF-8/BOM），改完如有 BOM 需剥离。
- `docs/BUSINESS-REVIEW.md`（gitignored，顶层）是历史审查台账——它描述的 org/ACL/权限层来自已废弃的 `master` 分支，当前代码库不存在该层，其修复引用均不适用；仅「删除即取消索引、owner 级秒传 CopyObject」与本代码库一致。LaTeX 项目总结也在 `docs/` 下。
