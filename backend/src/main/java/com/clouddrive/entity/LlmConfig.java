package com.clouddrive.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/** 用户 LLM 配置表 llm_configs（按供应商独立，密钥 AES-GCM 加密存储）。 */
@Entity
@Getter
@Setter
@Table(name = "llm_configs", uniqueConstraints = {
        @UniqueConstraint(name = "idx_user_provider", columnNames = {"user_id", "provider"}),
})
public class LlmConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(name = "base_url", length = 1024)
    private String baseUrl;

    @Column(name = "api_key_enc", columnDefinition = "TEXT")
    private String apiKeyEnc;

    @Column(length = 255)
    private String model;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
