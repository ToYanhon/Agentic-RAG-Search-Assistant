# backend-java 迁移计划清单

将 Go 后端（`backend/`）完整迁移到 Spring Boot Java 后端（`backend-java/`），目标行为级 parity。

## 技术选型

- JDK 21 LTS、Maven 3.9、Spring Boot 3.4.x+
- Spring Data JPA（`ddl-auto=none` + `schema.sql` 显式建表）
- Redis：spring-data-redis（Lettuce）+ `StringRedisTemplate`
- 安全：jjwt 0.12（HS256）、spring-security-crypto（BCrypt）、JCE AES-GCM
- MinIO：minio-java SDK 8.x
- Agent 代理：`java.net.http.HttpClient`（HTTP/1.1、流式、`Semaphore.tryAcquire`）
- Worker：`@EnableScheduling` + `@Scheduled`
- 测试：JUnit 5 + Mockito（无需基础设施，全 mock）

## 包结构（镜像 Go 领域分层）

```
src/main/java/com/clouddrive/
  CloudDriveApplication.java
  config/          Properties(CD_*), DotEnvLoader, JacksonConfig, ScheduleConfig
  common/          Envelope, ErrorCode, GlobalExceptionHandler, ApiException, Caller
  web/             AuthFilter, UserContext
  auth/            AuthService, AgentTokenManager, dto
  catalog/         CatalogService（uniqueName 算法、cycle 检测）
  file/            FileService, TextPolicy, Keys, DeletionWorker
  multipart/       MultipartService, MultipartPolicy, CleanupWorker
  share/           ShareService
  llmconfig/       LlmConfigService
  indexnotify/     IndexNotifyService, IndexSender
  agentproxy/      AgentProxyClient
  controller/      Auth/File/Folder/Multipart/Share/LlmConfig/Agent/Health Controller
  repository/      JPA 实体 + Repository（@Modifying 配额原子更新）
  adapter/redis/   Blacklist, ProfileCache, AgentTokenStore, ChecksumCache,
                   IndexNotifyQueue, MultipartMetadata, ShareCache
  adapter/security/ HS256JwtService, BcryptHasher, AesGcmCipher, CryptoRandom
  adapter/storage/ MinioObjectStore
  adapter/agent/   IndexSender
```

## Parity 清单（逐项核对 Go 行为）

- [x] 响应信封 `{code,message,data}`，`data` 为 null 时省略；code 常量 40000…42201/41300/41301
- [x] 健康/下载/分享下载/agent 代理为裸响应（无信封）
- [x] Bearer JWT：HS256、key=sha256(secret)、claims `user_id/username` + `sub/jti/iat/exp`、jti=16 字节 hex
- [x] `?token=` 仅 `/download` 后缀兜底；Agent 路径 `X-Agent-Token` + `user_id`（query 优先 header）
- [x] `PUT /files/{id}/content`、`POST /files/text` 仅 agent（否则 403）
- [x] 错误码→HTTP 映射逐端点复刻（agent busy→503/code 50000、unavailable→502/code 50000）
- [x] Redis key：`internal:agent:token`、`jti_blacklist:{jti}`、`task:index_notify`(LPUSH/RPOP)、`multipart:{id}`/`multipart:{id}:parts`(0-based、根=`"0"`、24h TTL)；Java 缓存用 `java:` 前缀
- [x] 配额原子更新 `storage_used+delta<=storage_limit` / `GREATEST(0,...)`
- [x] `WITH RECURSIVE` 子文件夹；uniqueUploadName `stem(N)ext` 去重 + 1062 重试
- [x] `object_delete_tasks` 指数退避（cap 30s）
- [x] 文件生命周期：上传（配额预占→写对象→建元数据→补偿）、秒传（同 owner MD5 + CopyObject 新 key）、覆盖（delta + 旧对象入 outbox）、删除/级联（unindex + outbox）
- [x] Multipart：默认 chunk 5MiB、合法 1–10MiB；非末块 ≥5MiB；complete 校验连续性 + MinIO headSize
- [x] Range 下载 `bytes=-N/S-/S-E`、416 + Content-Range；Content-Disposition、Accept-Ranges
- [x] Agent 代理：17 个 whitelist 路由、header 黑名单 + 注入 `X-User-Id/X-Agent-Token/X-LLM-*/X-Tavily-Key`（默认 provider `openai`）、32MiB body、32KiB flush（SSE）、并发 20
- [x] `CD_*` 环境变量 + `.env`（真实环境 > .env > 默认值）
- [x] Worker：agent token 旋转(15min/30min)、deletion(2s/批20)、index notify(2s/3次/退避)、multipart 清理(30min)
- [x] 已知 Go 怪癖 bug-for-bug：`POST /folders` PascalCase、下载 query token 按后缀判断

## 实施阶段

- [x] 写入计划清单文件（本文件）
- [x] P0 脚手架：pom.xml、主应用、CD_* 配置、DotEnv、信封/错误码/全局异常、Jackson
- [x] P1 数据层：schema.sql + JPA 实体/Repository + 配额原子更新
- [x] P2 安全与 Redis 适配器：JWT/BCrypt/AES-GCM/Random + Redis adapters
- [x] P3 认证 HTTP：AuthService/AgentTokenManager + AuthFilter + AuthController
- [x] P4 目录与文件：Catalog/File service + controllers + deletion worker + Range 下载
- [x] P5 Multipart/Share/LLMConfig 服务与控制器 + 清理 worker
- [x] P6 索引通知 + Agent 代理（SSE）+ AgentController
- [x] P7 组装：装配、@Scheduled、Dockerfile.backend-java、docker-compose、README
- [x] P8 测试移植：JUnit + Mockito 全量，mvn test/package 验证（97 tests 全绿，可执行 jar 产出）

## 契约 parity 烟测（已执行）

- [x] docker 基础设施（mysql/redis/minio/qdrant）+ Java(8080)/Go(8081) 并行运行
- [x] 注册/登录/登出+黑名单/改密/资料/用量 均与 Go 响应一致
- [x] 直传/秒传/Range 下载/?token= 回退/内容读取 均与 Go 一致
- [x] Multipart init/parts/complete（5MiB 非末块策略、headSize 校验）一致
- [x] 分享 create/access/download/revoke 一致
- [x] LLM 配置 save/list/掩码/delete 一致
- [x] Agent-only 端点 403 顺序（malformed body 也先返回 403）一致
- [x] Agent 代理不可达→502；busy 路径由并发测试覆盖
- [x] 跨后端 JWT 互通（Java 签发 token 可被 Go 解析，反之亦然）
- [x] 字节级比对：profile/storage/files/folders-root/llm-config/单文件/search/401/404 全部 SAME
- [x] 修复项：`@Modifying` 缺 `@Transactional`（llm-config 500）、`catalog.File` 泄漏 `owner_id`、JWT 缺 `typ` 头、agent-only 检查顺序

## 待确认决策

- [x] `POST /folders` PascalCase 响应：**已定案保持 snake_case**（匹配前端 Folder 类型）
- [x] docker-compose：**已定案并执行切换** —— `backend` 服务改用 Java 镜像（`Dockerfile.backend-java`，监听 8080），移除 `backend-java` 并行服务；agent/frontend 的 `backend:8080` 指向自动生效
- [x] 端到端验证：Java 单独跑通 Agent 全链路（双认证、索引 reindex/unindex、Chat SSE、Summary、Memory）后切换

## 验证

- `mvn -q test`（离线可跑，97 tests）、`mvn -q package`
- 起 docker infra 后对 Go/Java 后端跑同一组 curl 契约烟测（已完成，见上）
- 对照 `spec/api-contract-snapshot.md` 与前端 `src/api` 逐端点核对（已完成）
