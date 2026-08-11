package com.clouddrive.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.clouddrive.entity.LlmConfig;

/**
 * 用户 LLM 配置数据访问（对齐 Go llmConfigRepo，(user_id, provider) 唯一）。
 */
public interface LLMConfigRepository extends JpaRepository<LlmConfig, Long> {

    Optional<LlmConfig> findByUserIdAndProvider(Long userId, String provider);

    List<LlmConfig> findByUserIdOrderByProvider(Long userId);

    void deleteByUserIdAndProvider(Long userId, String provider);

    /** 按 (user_id, provider) 覆盖插入（对齐 Go OnConflict UpdateAll）。 */
    @Modifying
    @Query(value = """
            INSERT INTO llm_configs (user_id, provider, base_url, api_key_enc, model, updated_at)
            VALUES (:userId, :provider, :baseUrl, :apiKeyEnc, :model, NOW())
            ON DUPLICATE KEY UPDATE
              base_url = VALUES(base_url),
              api_key_enc = VALUES(api_key_enc),
              model = VALUES(model),
              updated_at = NOW()
            """, nativeQuery = true)
    void upsert(@Param("userId") Long userId, @Param("provider") String provider,
                @Param("baseUrl") String baseUrl, @Param("apiKeyEnc") String apiKeyEnc,
                @Param("model") String model);
}
