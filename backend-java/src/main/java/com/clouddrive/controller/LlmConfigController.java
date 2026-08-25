package com.clouddrive.controller;

import com.clouddrive.common.Envelope;
import com.clouddrive.llmconfig.LlmConfigService;
import com.clouddrive.llmconfig.View;
import com.clouddrive.web.UserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * LLM 配置控制器，对应 Go httpapi/llm_configs.go。
 */
@RestController
public class LlmConfigController {

	private final LlmConfigService service;

	public LlmConfigController(LlmConfigService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/llm-config")
	public Envelope<Map<String, List<View>>> list() {
		return Envelope.ok(Map.of("configs", service.list(UserContext.userId())));
	}

	public record LlmConfigRequest(String provider, String base_url, String api_key, String model) {
	}

	@PutMapping("/api/v1/llm-config")
	public Envelope<Void> save(@RequestBody LlmConfigRequest request) {
		service.upsert(UserContext.userId(), request.provider(), request.base_url(), request.api_key(),
				request.model());
		return Envelope.ok(null);
	}

	@DeleteMapping("/api/v1/llm-config/{provider}")
	public Envelope<Void> delete(@PathVariable("provider") String provider) {
		service.delete(UserContext.userId(), provider);
		return Envelope.ok(null);
	}

}