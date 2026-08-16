# AGENTS.md

Agentic-RAG-Search-Assistant 是 AI 云盘。三个服务由 `docker-compose.yml` 串联：Go backend 代理 Python Agent，frontend 调用 backend `/api/v1`。代码注释与文档使用中文，新增内容保持同风格。

## 目录结构

- `backend/` — Go 1.25 + MySQL/Redis/MinIO。入口 `cmd/cloud-drive/main.go`；HTTP 路由在 `internal/httpapi/`；业务 application/domain 在 `internal/auth`、`catalog`、`file`、`multipart`、`share`、`llmconfig`；基础设施适配器在 `internal/adapter/` 与 `internal/db/`。
- `agent/` — Python 3.12 + FastAPI，负责 Agent 编排、文件索引、检索、记忆和摘要。
- `frontend/` — React 18 + TypeScript + Vite + Tailwind。

## 业务边界

- 业务模型是 owner-only 个人云盘，文件、文件夹、分享和 LLM 配置均按 `owner_id` 隔离。
- 组织、部门、scope、ACL 和权限模型属于废弃设计，不得重新引入。
- API 保持 `/api/v1`、snake_case、响应信封和双认证契约稳定。
- 文件删除必须通知 unindex；新建、秒传、覆盖写和 multipart complete 必须通知 reindex。
- 秒传只能复用同一 owner 的对象，并复制到新的 object key。
- `?token=` 仅允许用于受保护的 `/download` 路由。

## 命令

- 基础设施：`docker compose up -d mysql redis minio qdrant`。MinIO 宿主机 API 端口是 `localhost:9100`。
- Backend：在 `backend/` 运行 `go run ./cmd/cloud-drive`、`go test ./...`、`go vet ./...`、`go build ./cmd/cloud-drive`。本地配置可选 `copy .env.example .env`（godotenv 读取，真实环境变量优先）。
- Agent：在 `agent/` 配置 `.env`，运行 `pip install -r requirements.txt`、`pytest test/`、`uvicorn app.main:app --reload --port 8000`。
- Frontend：在 `frontend/` 运行 `npm install`、`npm run build`、`npm run dev`。
- 启动 Go 后端验证时使用 `Start-Process` 后台运行并轮询 `/health`，不要阻塞等待。

## 后端约束

- Go application/domain 层只依赖 consumer-defined ports；MySQL、Redis、MinIO、Agent 和安全实现留在 `internal/adapter` 或 `internal/db`。
- 用户请求使用 Bearer JWT；Agent 请求使用 `X-Agent-Token` 和可信 `X-User-Id`。
- `PUT /files/{id}/content` 和 `POST /files/text` 仅 Agent 可调用；文本读取对 user/agent 开放。
- 直传上限 50MB，multipart 单块上限 10MB，单文件上限 10GB。
- Agent 无入站鉴权，只能通过后端访问；Docker Compose 仅绑定 Agent 的本机端口。
- 不引入任意命令执行；文件操作必须经过后端 owner 校验。
