package com.clouddrive.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.Data;

/**
 * 用户 LLM 配置 DTO（apiKey 仅脱敏展示，对齐 Go LLMConfigDTO）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class LLMConfigDTO {

    private String provider;
    private String baseUrl;
    private String apiKeyMasked;
    private String model;
    /** base_url/api_key/model 三要素齐全才可聊天。 */
    private boolean configured;
    private LocalDateTime updatedAt;
}
