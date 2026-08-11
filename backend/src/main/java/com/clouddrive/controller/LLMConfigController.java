package com.clouddrive.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.clouddrive.common.Resp;
import com.clouddrive.dto.LLMConfigDTO;
import com.clouddrive.service.LLMConfigService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户 LLM 配置接口（对齐 Go llm_config_handler）。
 */
@RestController
@RequestMapping("/api/v1/llm-config")
public class LLMConfigController {

    private final LLMConfigService service;

    public LLMConfigController(LLMConfigService service) {
        this.service = service;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class SaveReq {
        @NotBlank
        @Size(max = 64)
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String model;
    }

    @GetMapping
    public Resp<Map<String, Object>> list(HttpServletRequest request) {
        List<LLMConfigDTO> configs = service.list(userId(request));
        return Resp.ok(Map.of("configs", configs));
    }

    @PutMapping
    public Resp<Void> save(@Valid @RequestBody SaveReq req, HttpServletRequest request) {
        service.upsert(userId(request), req.getProvider(), req.getBaseUrl(),
                req.getApiKey(), req.getModel());
        return Resp.ok(null);
    }

    @DeleteMapping("/{provider}")
    public Resp<Void> delete(@PathVariable String provider, HttpServletRequest request) {
        if (provider == null || provider.trim().isEmpty()) {
            throw com.clouddrive.common.AppException.badRequest("provider required");
        }
        service.delete(userId(request), provider.trim());
        return Resp.ok(null);
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("user_id");
    }
}
