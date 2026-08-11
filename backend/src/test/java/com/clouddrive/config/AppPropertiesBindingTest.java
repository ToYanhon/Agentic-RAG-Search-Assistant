package com.clouddrive.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 配置契约测试：CD_&lt;SECTION&gt;_&lt;FIELD&gt; 环境变量映射 + Spring 绑定到 AppProperties。
 */
class AppPropertiesBindingTest {

    @Test
    void cdEnvKeyMapsToDotKebabConfigKey() {
        assertThat(CDEnvironmentPostProcessor.map("CD_SERVER_PORT")).isEqualTo("app.server.port");
        assertThat(CDEnvironmentPostProcessor.map("CD_MINIO_USE_SSL")).isEqualTo("app.minio.use-ssl");
        assertThat(CDEnvironmentPostProcessor.map("CD_LLM_ENCRYPTION_KEY")).isEqualTo("app.llm.encryption-key");
        assertThat(CDEnvironmentPostProcessor.map("CD_UPLOAD_DIRECT_MAX_BYTES")).isEqualTo("app.upload.direct-max-bytes");
        assertThat(CDEnvironmentPostProcessor.map("CD_AGENT_BASE_URL")).isEqualTo("app.agent.base-url");
        assertThat(CDEnvironmentPostProcessor.map("CD_AGENT_RESPONSE_HEADER_TIMEOUT_SEC"))
                .isEqualTo("app.agent.response-header-timeout-sec");
        // 未知 section / 非 CD_ 前缀不映射
        assertThat(CDEnvironmentPostProcessor.map("CD_FOO_BAR")).isNull();
        assertThat(CDEnvironmentPostProcessor.map("NOPE")).isNull();
    }

    @Test
    void mappedPropertiesBindToAppProperties() {
        Map<String, Object> src = new HashMap<>();
        src.put("app.server.port", "8081");
        src.put("app.minio.use-ssl", "true");
        src.put("app.upload.direct-max-bytes", "999");
        src.put("app.jwt.expire-hours", "48");
        src.put("app.llm.encryption-key", "k".repeat(32));

        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", src));

        AppProperties app = Binder.get(env).bind("app", AppProperties.class).get();

        assertThat(app.getServer().getPort()).isEqualTo(8081);
        assertThat(app.getMinio().isUseSsl()).isTrue();
        assertThat(app.getUpload().getDirectMaxBytes()).isEqualTo(999);
        assertThat(app.getJwt().getExpireHours()).isEqualTo(48);
        assertThat(app.getLlm().getEncryptionKey()).hasSize(32);
        // 未覆盖字段保持默认
        assertThat(app.getAgent().getBaseUrl()).isEqualTo("http://127.0.0.1:8000");
    }
}
