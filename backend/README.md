# Agentic-RAG-Search-Assistant 后端（Go）

Go 后端保持既有 HTTP、数据和基础设施契约：响应信封、错误码、JWT/双认证、`/s/:token`、
owner 级秒传、删除即取消索引、`X-Agent-Token` 轮换等全部保持，Python Agent 与前端无需改动。

## 技术栈

- Go 1.25 + `net/http`
- MySQL 8、Redis 7、MinIO S3、Qdrant、Python Agent
- JWT、bcrypt、AES-GCM 和对象存储均通过 Go adapter 接入

## 目录结构

```
cmd/cloud-drive/          # 组合根与 HTTP server
internal/httpapi/         # REST/SSE 路由和响应映射
internal/{auth,catalog,file,multipart,share,llmconfig}/ # application/domain
internal/adapter/         # MySQL、Redis、MinIO、Agent、安全适配器
internal/db/              # MySQL 持久化适配器
```

## 运行

```bash
go run ./cmd/cloud-drive
go test ./...
```

需要基础设施：`docker compose up -d mysql redis minio qdrant`（MinIO API 端口 `localhost:9100`）。

### 配置覆盖（与 Go 契约一致）

- `APP_MODE` 选择 profile（dev/prod）
- 任意字段 `CD_<SECTION>_<FIELD>` 覆盖，如 `CD_SERVER_PORT=8081`、`CD_MINIO_USE_SSL=true`、
  `CD_LLM_ENCRYPTION_KEY=<>=32字节>`（prod 必设）、`CD_UPLOAD_DIRECT_MAX_BYTES`

## 当前进度

| 阶段 | 状态 |
|---|---|
| 认证、目录、文件生命周期和分享 | ✅ 已完成 |
| 分块上传、配额、对象存储和索引通知 | ✅ 已完成 |
| LLM 配置与 Agent SSE 代理 | ✅ 已完成 |
| 真实 MySQL/Redis/MinIO E2E 与 Docker 构建 | ✅ 已验证 |
