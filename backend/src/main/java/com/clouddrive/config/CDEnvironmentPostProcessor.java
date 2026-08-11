package com.clouddrive.config;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 保持 Go backend 的配置覆盖契约：{@code CD_<SECTION>_<FIELD>} 环境变量
 * 覆盖对应配置（如 CD_SERVER_PORT / CD_MINIO_USE_SSL / CD_LLM_ENCRYPTION_KEY）。
 *
 * 规则：按已知 section 前缀切分，映射为与 application.yml 完全一致的点分 kebab 键，
 * 例如 CD_MINIO_USE_SSL → app.minio.use-ssl；这样既满足 yml 占位符 ${app.*} 的精确解析，
 * 也能被 Spring 宽松绑定落入 AppProperties。覆盖源置于最前，优先于 yml 默认值。
 */
public class CDEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Set<String> SECTIONS = Set.of(
            "server", "mysql", "redis", "minio", "jwt", "agent", "log", "upload", "llm");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> props = new HashMap<>();
        environment.getSystemEnvironment().forEach((key, value) -> {
            String mapped = map(key);
            if (mapped != null && value != null) {
                props.put(mapped, value);
            }
        });
        if (!props.isEmpty()) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("cdEnvOverrides", props));
        }
    }

    static String map(String envKey) {
        if (!envKey.startsWith("CD_")) {
            return null;
        }
        for (String section : SECTIONS) {
            String prefix = "CD_" + section.toUpperCase() + "_";
            if (envKey.startsWith(prefix)) {
                String field = envKey.substring(prefix.length()).toLowerCase().replace('_', '-');
                if (field.isEmpty()) {
                    return null;
                }
                return "app." + section + "." + field;
            }
        }
        return null;
    }
}
