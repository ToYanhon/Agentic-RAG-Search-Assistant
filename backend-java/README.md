# backend-java

CloudDrive AI 云盘后端（Java Spring Boot 迁移版），行为级对齐 `../backend`（Go）的 HTTP 契约、错误码、Redis key 与后台 worker。

## 技术栈

- Java 21 LTS + Maven 3.9 + Spring Boot 3.4
- Spring Data JPA（`ddl-auto=none` + `schema.sql` 幂等建表）
- Redis（Lettuce + `StringRedisTemplate`）
- 安全：jjwt（HS256，`sha256(secret)` 派生密钥）、spring-security-crypto（BCrypt）、JCE AES-GCM（`v1:` 前缀）
- MinIO：AWS S3 SDK v2（path-style，完整 S3 multipart）
- Agent 代理：`java.net.http.HttpClient`（HTTP/1.1）+ `Semaphore` 并发限流
- Worker：`@Scheduled`（agent token 轮换 15min、删除 outbox 2s、索引通知 2s、multipart 清理 30min）

## 目录结构（镜像 Go 分层）

```
src/main/java/com/clouddrive/
  config/       AppProperties(CD_*)、DotEnvLoader、RedisConfig、安全 bean
  common/       Envelope、ErrorCode、GlobalExceptionHandler、Errors、ApiException
  web/          AuthFilter、UserContext、Downloader、Responses
  auth/         AuthService、AgentTokenManager、端口与模型
  catalog/      CatalogService（去重/循环检测）
  file/         FileService、RandomKey、TextPolicy、DeletionWorker
  multipart/    MultipartService、MultipartPolicy、CleanupWorker
  share/        ShareService
  llmconfig/    LlmConfigService（AES-GCM 加密）
  indexnotify/  IndexNotifyService（队列+重试）
  agentproxy/   AgentProxyClient（header 注入 + SSE）
  controller/   Auth/File/Folder/Multipart/Share/LlmConfig/Agent/Health
  repository/   JPA 实体 + 仓库（配额原子更新、递归 CTE、outbox）
  adapter/      redis / security / storage / agent / clock
```

## 配置

`CD_*` 环境变量（Spring relaxed binding）+ `.env` 支持，优先级：真实环境变量 > `.env` > 默认值。
复制 `.env.example` 为 `.env` 后按需修改。

## 命令

- 基础设施：`docker compose up -d mysql redis minio qdrant`（仓库根执行）
- 构建/测试：`mvn -q -DskipTests package`、`mvn -q test`
- 质量门禁（强制）：`mvn verify` = 测试 + Checkstyle + SpotBugs + spring-javaformat 校验，任一违规即失败
- 格式化：`mvn spring-javaformat:apply`
- 本地运行：`mvn spring-boot:run`（默认 8080）
- 容器：仓库根 `docker compose build backend`（`backend` 服务已切换为 Java 镜像，监听 8080）

### Lint 配置

- `config/checkstyle/checkstyle.xml`：聚焦结构/易错规则（导入、空语句、switch 穿透、equals/hashCode 等）；
  缩进/换行/导入顺序由 spring-javaformat 负责，避免与格式化器重复冲突。
- `config/spotbugs/exclude.xml`：排除经评估的误报（Spring DI 的 `EI_EXPOSE_REP*`、不可变 record 访问器、
  `Record` 类名直译、Range 解析 null 哨兵、构造校验抛出、尽力而为通知），其余修复。
- SpotBugs 通过代码修复清零：Locale 大小写、收窄 catch、方法命名、删除多余 NPE catch。

## Parity 与已知偏差

- 响应信封、错误码（40000…42201/41300/41301）、`data` 为 null 省略、`?token=` 仅 `/download` 后缀：与 Go 一致。
- Redis 共享 key（`internal:agent:token`、`jti_blacklist:{jti}`、`task:index_notify`、`multipart:{id}` 等）与 Go/Agent 契约一致；
  Java 自有缓存使用 `java:` 前缀（Go 为 `go:`）。
- **POST /folders 响应**：Go 因 `catalog.Folder` 无 json tag 输出 PascalCase（`{"ID":...}`）——Java 版本输出 snake_case，符合前端 `Folder` 类型；属有意修正（已定案）。
- **Agent header timeout**：Go 用 `ResponseHeaderTimeout=60s`；Java 通过 `sendAsync().get(headerTimeout)` 近似实现（SSE 长流不受影响）。
- MySQL 时间戳：Java 按 `Asia/Shanghai` 连接时区读写，`created_at` 输出保持 `yyyy-MM-dd'T'HH:mm:ss'Z'` 格式。
- 对象删除/索引通知均为尽力而为：Redis/Agent 不可达不阻塞文件主流程。

## 契约 parity 验证（已完成）

Docker 基础设施 + Java(8080)/Go(8081) 并行运行时，对同一组请求做字节级比对，以下均 **SAME**：
- 认证：登录资料、`/auth/profile`、`/auth/storage/usage`
- 文件：`/files` 列表（`owner_id` 不序列化，对应 Go `json:"-"`）、单文件、搜索
- 目录：`/folders/root`、文件夹树
- 配置：`/llm-config`
- 错误：未带 token 401、不存在 404（信封文本一致）
- JWT 互通：Java 签发 token 可被 Go 解析，反之亦然（同一 `sha256(secret)` 密钥 + `typ:JWT`）

## 端到端验证（已完成，已切换）

对 **Java 后端（8080）单独**跑完整 Agent 全链路，全部通过，随后已把 docker-compose 的 `backend` 服务切换为 Java 镜像：
- Agent↔后端双认证闭环（`internal:agent:token` 轮换 + Agent 回访 Java `/files/{id}`、`/download`、`/folders/{id}` 均 200）
- 索引通知 reindex/unindex：上传→`task:index_notify`→Agent 消费→本地 embedding→Qdrant 写入/删除→index status `true/false`
- Chat SSE 流式：经 Java 代理增量 `data:` 文本 → `meta`（model/usage/cost）→ `[DONE]`；`X-LLM-Key/Base-URL/Model` 由 Java 从 AES-GCM 加密存储注入
- Summary（真实 LLM）、Memory 提取（`save_memory` 工具）与增删、会话历史/列表

烟测中发现并修复的问题：
1. **`@Modifying` 查询缺 `@Transactional`** → llm-config PUT 曾 500；已补到 `UserJpa`/`CatalogJpa`/`ShareJpa`/`LlmConfigJpa` 的写方法。
2. **`catalog.File` 泄漏 `owner_id`** → 加 `@JsonIgnore`（对应 Go `json:"-"`）。
3. **JWT 头缺 `typ:JWT`** → jjwt builder 增加 `.header().type("JWT")`。
4. **Agent-only 检查顺序** → `POST /files/text`、`PUT /files/{id}/content` 改为先校验 caller 再解析 body（Go 语义：非 agent 即使 body 非法也返回 403）。

其余行为（直传/秒传/Range 下载/multipart 策略/分享/登出黑名单/Agent 502）均与 Go 一致。