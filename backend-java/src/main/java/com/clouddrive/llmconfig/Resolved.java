package com.clouddrive.llmconfig;

/**
 * 解析后的 LLM 配置（仅 Agent 代理注入使用），对应 Go llmconfig.Resolved。
 */
public record Resolved(String baseUrl, String apiKey, String model, boolean ok) {

	public static Resolved none() {
		return new Resolved(null, null, null, false);
	}
}