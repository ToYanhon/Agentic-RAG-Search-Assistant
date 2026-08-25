package com.clouddrive.llmconfig;

import java.time.LocalDateTime;

/**
 * 加密存储的 LLM 配置，对应 Go llmconfig.Stored。
 */
public record Stored(String provider, String baseUrl, String apiKeyEnc, String model, LocalDateTime updatedAt) {
}