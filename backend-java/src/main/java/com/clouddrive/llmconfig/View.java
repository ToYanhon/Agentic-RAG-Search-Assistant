package com.clouddrive.llmconfig;

/**
 * LLM 配置视图，对应 Go llmconfig.View。api_key_masked 仅展示掩码。
 */
public record View(String provider, String baseUrl, String apiKeyMasked, String model, boolean configured,
		String updatedAt) {
}