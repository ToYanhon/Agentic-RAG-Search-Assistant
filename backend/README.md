# CloudDrive AI 后端（Java/Spring Boot 迁移版）

原 Go 后端（`../backend/`）的 Java/Spring Boot 迁移实现，**契约 1:1 对齐**：响应信封、错误码、
JWT/双认证、`/s/:token`、秒传 owner 语义、删除即取消索引、`X-Agent-Token` 轮换等全部保持，
Python agent 与前端**零改动**。

> 迁移已完成（P0–P6 全部落地、e2e 验证通过），docker-compose 已切换到此实现，Go 后端已删除。

## 技术栈

- Java 21（LTS）、Spring Boot 3.3.x、Maven
- Spring MVC（servlet，SSE/下载用流式，不引入 WebFlux）
- Spring Data JPA（Hibernate）+ MySQL 8
- Spring Data Redis + Lettuce；MinIO Java SDK
- JWT 走**自定义过滤器**（jjwt），不引入完整 Spring Security；BCrypt 仅取 `spring-security-crypto`
- springdoc-openapi（Swagger UI，dev 开启）

## 目录结构

```
src/main/java/com/clouddrive/
  config/       # AppProperties 绑定、CD_ 环境变量覆盖、MinIO/Redis/Web(CORS) 装配
  common/       # Resp 信封、ErrorCode、AppException、全局异常处理
  security/     # JwtService、AgentTokenManager、AuthFilter（P1）
  controller/   # REST 控制器（P1+）
  service/      # 业务层（P1+）
  repository/   # Spring Data JPA（P1+）
  entity/       # JPA 实体（users/files/folders/shares/llm_configs）
  proxy/        # Agent 客户端 + SSE 透传（P4）
src/main/resources/
  application.yml        # 公共 + dev 默认值
  application-dev.yml
  application-prod.yml   # docker 服务名
```

## 运行

```bash
mvn spring-boot:run            # dev，默认 127.0.0.1:8080
java -jar target/backend-0.1.0.jar
```

需要基础设施：`docker compose up -d mysql redis minio qdrant`（MinIO API 端口 `localhost:9100`）。

### 配置覆盖（与 Go 契约一致）

- `APP_MODE` 选择 profile（dev/prod）
- 任意字段 `CD_<SECTION>_<FIELD>` 覆盖，如 `CD_SERVER_PORT=8081`、`CD_MINIO_USE_SSL=true`、
  `CD_LLM_ENCRYPTION_KEY=<>=32字节>`（prod 必设）、`CD_UPLOAD_DIRECT_MAX_BYTES`

## 当前进度

| 阶段 | 状态 |
|---|---|
| P0 脚手架（配置/契约/实体/Bean/健康检查） | ✅ 已完成（启动验证通过，表结构对齐） |
| P1 认证（JWT + 双认证 + bcrypt + 黑名单） | ✅ 已完成（curl 全链路验证 + JUnit） |
| P2 文件域（直传/秒传/分块/文件夹/配额/删除即 unindex） | ✅ 已完成（真实 MySQL/Redis/MinIO 全链路验证 + JUnit） |
| P3 分享（/s/:token + Redis 缓存 + 过期/撤销） | ✅ 已完成（公开访问/下载/过期/撤销/越权验证） |
| P4 代理与 LLM 配置（AES-GCM + SSE 透传 + X-LLM-* 注入） | ✅ 已完成（mock agent 直证注入与 SSE；23 个 JUnit） |
| P5 后台任务（multipart 清理/agent token 轮换）+ 优雅关闭 + OpenAPI | ✅ 已完成 |
| P6 切换 docker-compose + e2e + 删 Go | ✅ 已完成（`Dockerfile.backend` + compose 切换；agent e2e 经真实 agent 全链路通过） |
