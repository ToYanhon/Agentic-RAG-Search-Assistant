# 项目上下文

## 当前运行架构

AI 云盘由 Go 后端、Python Agent 和 React 前端组成。Docker Compose 将 frontend、backend、agent 与 MySQL、Redis、MinIO、Qdrant 连接起来。

- 前端通过 `/api/v1` 调用 Go 后端。
- 后端代理受控的 Agent 请求，并向 Agent 注入可信用户身份和已保存的 LLM 配置。
- Agent 负责对文件建立向量索引、检索和多轮对话，不直接连接 MySQL 或 MinIO。
- MinIO 使用 S3 path-style；宿主机开发端口是 `localhost:9100`。

## 契约与不变量

- API 路径以 `/api/v1` 为前缀，DTO 使用 snake_case，成功响应为 `{"code":0,"message":"success","data":...}`。
- 用户请求使用 Bearer JWT；内部 Agent 请求使用 `X-Agent-Token` 与可信 `X-User-Id`。
- `?token=` 仅可用于以 `/download` 结尾的受保护下载路由。
- 所有文件、文件夹、分享和 LLM 配置操作必须限制在 owner 范围内。
- 秒传只能复用同一 owner 的文件，并需复制到新的对象 key。
- 文件删除必须通知 unindex；新建和内容覆盖必须通知 reindex。
- 统一 Redis agent token key 为 `internal:agent:token`，JWT 黑名单 key 为 `jti_blacklist:{jti}`。

## Go 后端

`backend/` 使用 Go 1.25，实现认证、目录、文件生命周期、分块上传、分享、LLM 配置和 Agent 代理。

- `cmd/cloud-drive/main.go` 是组合根。
- `internal/httpapi/` 提供 HTTP 路由和响应映射。
- `internal/auth`、`catalog`、`file`、`multipart`、`share`、`llmconfig` 承担 application/domain 规则。
- `internal/adapter/` 和 `internal/db/` 承担 MySQL、Redis、MinIO、Agent 与安全基础设施适配。
- 资源权限边界只有 owner 隔离；组织、部门、scope 和 ACL/权限模型均已废弃，不得重新引入。

## 本地验证

- 基础设施：`docker compose up -d mysql redis minio qdrant`。
- Go 后端：在 `backend/` 运行 `go test ./...`、`go vet ./...`、`go build ./cmd/cloud-drive`；运行服务用 `go run ./cmd/cloud-drive`。
- Agent：在 `agent/` 运行 `pytest test/`。
- Frontend：在 `frontend/` 运行 `npm run build`。
