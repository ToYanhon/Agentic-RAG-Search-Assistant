package com.clouddrive.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.clouddrive.common.Cryptox;
import com.clouddrive.dto.LLMConfigDTO;
import com.clouddrive.entity.LlmConfig;
import com.clouddrive.repository.LLMConfigRepository;

/**
 * 用户 LLM 配置（按供应商独立，apiKey AES-256-GCM 加密落盘，对齐 Go llmConfigService）。
 * 主密钥须 ≥32 字节（dev 有默认占位，prod 必须 CD_LLM_ENCRYPTION_KEY 提供），SHA-256 派生 32B。
 */
@Service
public class LLMConfigService {

    private final LLMConfigRepository repo;
    private final byte[] masterKey;

    public LLMConfigService(LLMConfigRepository repo, com.clouddrive.config.AppProperties props) {
        this.repo = repo;
        String key = props.getLlm().getEncryptionKey();
        if (key == null || key.length() < 32) {
            throw new IllegalStateException(
                    "llm encryption key must be at least 32 bytes, set CD_LLM_ENCRYPTION_KEY");
        }
        try {
            this.masterKey = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Transactional
    public void upsert(Long userId, String provider, String baseUrl, String apiKey, String modelName) {
        provider = provider == null ? "" : provider.trim();
        if (provider.isEmpty()) {
            throw com.clouddrive.common.AppException.badRequest("provider required");
        }
        String enc = repo.findByUserIdAndProvider(userId, provider)
                .map(LlmConfig::getApiKeyEnc).orElse("");
        if (apiKey != null && !apiKey.isEmpty()) {
            enc = Cryptox.encrypt(masterKey, apiKey.getBytes(StandardCharsets.UTF_8));
        }
        repo.upsert(userId, provider, normalizeBaseURL(baseUrl), enc,
                modelName == null ? "" : modelName.trim());
    }

    public List<LLMConfigDTO> list(Long userId) {
        return repo.findByUserIdOrderByProvider(userId).stream()
                .map(r -> {
                    LLMConfigDTO dto = new LLMConfigDTO();
                    String baseUrl = normalizeBaseURL(r.getBaseUrl());
                    String masked = r.getApiKeyEnc() == null || r.getApiKeyEnc().isEmpty() ? "" : "******";
                    dto.setProvider(r.getProvider());
                    dto.setBaseUrl(baseUrl);
                    dto.setApiKeyMasked(masked);
                    dto.setModel(r.getModel());
                    dto.setConfigured(!baseUrl.isEmpty() && !masked.isEmpty() && !isEmpty(r.getModel()));
                    dto.setUpdatedAt(r.getUpdatedAt());
                    return dto;
                })
                .toList();
    }

    /** 取该用户某供应商的解密配置用于代理注入；未配置/解密失败 ok=false。 */
    public ResolveResult resolve(Long userId, String provider) {
        LlmConfig cfg = repo.findByUserIdAndProvider(userId, provider).orElse(null);
        if (cfg == null || cfg.getApiKeyEnc() == null || cfg.getApiKeyEnc().isEmpty()) {
            return new ResolveResult(null, null, null, false);
        }
        try {
            String plain = new String(Cryptox.decrypt(masterKey, cfg.getApiKeyEnc()),
                    StandardCharsets.UTF_8);
            return new ResolveResult(normalizeBaseURL(cfg.getBaseUrl()), plain, cfg.getModel(), true);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(LLMConfigService.class)
                    .warn("llm config resolve decrypt failed: uid={} provider={}", userId, provider);
            return new ResolveResult(null, null, null, false);
        }
    }

    public record ResolveResult(String baseUrl, String apiKey, String model, boolean ok) {
    }

    @Transactional
    public void delete(Long userId, String provider) {
        repo.deleteByUserIdAndProvider(userId, provider);
    }

    /** 归一化 Base URL：去掉尾部 /chat/completions、尾部 / 与 query。 */
    static String normalizeBaseURL(String raw) {
        if (raw == null) {
            return "";
        }
        String u = raw.trim();
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        u = stripTrailingSlash(u);
        String suffix = "/chat/completions";
        if (u.length() >= suffix.length()
                && u.substring(u.length() - suffix.length()).equalsIgnoreCase(suffix)) {
            u = stripTrailingSlash(u.substring(0, u.length() - suffix.length()));
        }
        return u;
    }

    private static String stripTrailingSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
