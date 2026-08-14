package com.clouddrive.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时确保 files 表唯一索引（IMPROVEMENTS.md D4）：
 * 根目录 folder_id 为 NULL，普通唯一索引对 NULL 不生效（多个 NULL 视为互不相同），
 * 故用生成列 folder_key = COALESCE(folder_id, 0) 归一后建普通唯一索引 (owner_id, folder_key, name)。
 *
 * 注意：不能用 MySQL 函数式索引 (COALESCE(folder_id,0))——其元数据把表达式当列名，
 * Hibernate ddl-auto=update 在二次启动读取索引时 getColumn(表达式) 返回 null 直接崩溃；
 * 生成列是真实列名，Hibernate 可正常读取（对其不可见，也不会被 update 删除）。
 *
 * 幂等：先查 information_schema 跳过已存在；存量库已有重名数据时建索引失败仅告警（不阻断启动，需清理后重跑）。
 */
@Component
public class DbIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DbIndexInitializer.class);

    private static final String INDEX_NAME = "uk_files_owner_folder_name";
    private static final String FOLDER_KEY_COL = "folder_key";

    private final JdbcTemplate jdbc;

    public DbIndexInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // 兼容旧实现遗留的函数式索引（若存在先删，避免 Hibernate 二次启动崩溃）
            if (!indexMissing("files", INDEX_NAME)) {
                jdbc.execute("ALTER TABLE files DROP INDEX " + INDEX_NAME);
                log.info("dropped legacy functional index {}", INDEX_NAME);
            }
            if (columnMissing("files", FOLDER_KEY_COL)) {
                jdbc.execute("ALTER TABLE files ADD COLUMN " + FOLDER_KEY_COL
                        + " INT GENERATED ALWAYS AS (COALESCE(folder_id, 0)) STORED");
                log.info("added generated column files.{}", FOLDER_KEY_COL);
            }
            if (indexMissing("files", INDEX_NAME)) {
                jdbc.execute("CREATE UNIQUE INDEX " + INDEX_NAME
                        + " ON files (owner_id, " + FOLDER_KEY_COL + ", name)");
                log.info("created unique index {}", INDEX_NAME);
            }
        } catch (Exception e) {
            log.warn("failed to ensure unique index {}: {}", INDEX_NAME, e.toString());
        }
    }

    private boolean columnMissing(String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return n == null || n == 0;
    }

    private boolean indexMissing(String table, String index) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        return n == null || n == 0;
    }
}
