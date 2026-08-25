-- CloudDrive AI 后端（Java）数据库结构。
-- 与 Go 后端 internal/db 的查询语义保持一致；幂等可重复执行（CREATE TABLE IF NOT EXISTS）。
-- 不声明 InnoDB 外键约束（对齐 Go 后端行为：级联删除顺序由业务层控制）。

CREATE TABLE IF NOT EXISTS users (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    email         VARCHAR(128) NOT NULL,
    password      VARCHAR(256) NOT NULL,
    storage_used  BIGINT       NOT NULL DEFAULT 0,
    storage_limit BIGINT       NOT NULL DEFAULT 1073741824,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS folders (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    parent_id  BIGINT      NULL,
    owner_id   BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_folders_owner (owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS files (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id   BIGINT       NOT NULL,
    folder_id  BIGINT       NULL,
    name       VARCHAR(255) NOT NULL,
    size       BIGINT       NOT NULL,
    mime_type  VARCHAR(128) NULL,
    md5        VARCHAR(32)  NULL,
    object_key VARCHAR(512) NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    folder_key BIGINT GENERATED ALWAYS AS (coalesce(folder_id, 0)) STORED,
    scope      VARCHAR(16)  NOT NULL DEFAULT 'personal',
    org_id     BIGINT       NULL,
    dept_id    BIGINT       NULL,
    UNIQUE KEY uk_files_owner_folder_name (owner_id, folder_key, name),
    KEY idx_files_md5 (md5),
    KEY idx_files_folder (folder_id),
    KEY idx_files_owner (owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS shares (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id   BIGINT       NOT NULL,
    file_id    BIGINT       NOT NULL,
    token      VARCHAR(64)  NOT NULL,
    expired_at DATETIME(6)  NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_shares_token (token),
    KEY idx_shares_owner (owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS llm_configs (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    provider    VARCHAR(64)   NOT NULL,
    base_url    VARCHAR(1024) NULL,
    api_key_enc TEXT          NULL,
    model       VARCHAR(255)  NULL,
    updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY idx_user_provider (user_id, provider)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS object_delete_tasks (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    object_key     VARCHAR(512) NOT NULL,
    attempts       INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_object_delete_tasks_due (next_attempt_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
