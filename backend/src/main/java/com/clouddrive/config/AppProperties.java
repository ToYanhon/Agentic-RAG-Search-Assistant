package com.clouddrive.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置（前缀 app），与 Go backend 的 config.yaml 各 section 对齐。
 * 任意字段可由环境变量 CD_&lt;SECTION&gt;_&lt;FIELD&gt; 覆盖（见 CDEnvironmentPostProcessor）。
 */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Server server = new Server();
    private Mysql mysql = new Mysql();
    private Redis redis = new Redis();
    private Minio minio = new Minio();
    private Jwt jwt = new Jwt();
    private Agent agent = new Agent();
    private Upload upload = new Upload();
    private Llm llm = new Llm();

    @Data
    public static class Server {
        private int port = 8080;
        private String mode = "dev";
    }

    @Data
    public static class Mysql {
        private String host = "localhost";
        private int port = 3306;
        private String user = "root";
        private String password = "root";
        private String database = "cloud_drive";
    }

    @Data
    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
    }

    @Data
    public static class Minio {
        private String endpoint = "localhost:9100";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "cloud-drive";
        private boolean useSsl = false;
    }

    @Data
    public static class Jwt {
        private String secret = "change-me-in-production";
        private int expireHours = 72;
    }

    @Data
    public static class Agent {
        private String baseUrl = "http://127.0.0.1:8000";
        private int responseHeaderTimeoutSec = 60;
        private int maxConcurrent = 20;
    }

    @Data
    public static class Upload {
        /** 直传上限（字节） */
        private long directMaxBytes = 52_428_800;
        /** 分块单块上限（字节） */
        private long chunkMaxBytes = 10_485_760;
        /** 分块文件总上限（字节） */
        private long fileMaxBytes = 10_737_418_240L;
    }

    @Data
    public static class Llm {
        /** AES-256-GCM 主密钥，须 >=32 字节；生产必须 CD_LLM_ENCRYPTION_KEY 覆盖 */
        private String encryptionKey = "dev-only-llm-encryption-key-change-me-32b";
    }
}
