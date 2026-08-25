package com.clouddrive.llmconfig;

import com.clouddrive.common.Errors;
import com.clouddrive.common.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 配置用例，对应 Go llmconfig.Service。
 */
@org.springframework.stereotype.Service
public class LlmConfigService {

	private final Repository configs;

	private final Secret secrets;

	public LlmConfigService(Repository configs, Secret secrets) {
		this.configs = configs;
		this.secrets = secrets;
	}

	public void upsert(long userId, String provider, String baseUrl, String apiKey, String model) {
		String normalizedProvider = provider == null ? "" : provider.trim();
		if (normalizedProvider.isEmpty()) {
			throw new Errors.ProviderRequired("provider required");
		}
		if (normalizedProvider.length() > 64) {
			throw new Errors.ProviderTooLong("provider too long");
		}
		String enc = "";
		try {
			Stored existing = configs.find(userId, normalizedProvider);
			enc = existing.apiKeyEnc();
		}
		catch (Errors.LlmConfigNotFound ignored) {
			// 不存在则全新建
		}
		if (apiKey != null && !apiKey.isEmpty()) {
			enc = secrets.encrypt(apiKey);
		}
		configs.upsert(userId, normalizedProvider, normalizeBaseUrl(baseUrl), enc, model == null ? "" : model.trim());
	}

	public List<View> list(long userId) {
		List<Stored> stored = configs.findAll(userId);
		List<View> views = new ArrayList<>(stored.size());
		for (Stored value : stored) {
			String baseUrl = normalizeBaseUrl(value.baseUrl());
			String masked = "";
			if (value.apiKeyEnc() != null && !value.apiKeyEnc().isEmpty()) {
				masked = "******";
			}
			String updated = TimeUtil.format(value.updatedAt());
			boolean configured = !baseUrl.isEmpty() && !masked.isEmpty() && value.model() != null
					&& !value.model().trim().isEmpty();
			views.add(new View(value.provider(), baseUrl, masked, value.model(), configured, updated));
		}
		return views;
	}

	public void delete(long userId, String provider) {
		String normalized = provider == null ? "" : provider.trim();
		if (normalized.isEmpty()) {
			throw new Errors.ProviderRequired("provider required");
		}
		configs.delete(userId, normalized);
	}

	public Resolved resolve(long userId, String provider) {
		Stored stored;
		try {
			stored = configs.find(userId, provider);
		}
		catch (RuntimeException e) {
			return Resolved.none();
		}
		if (stored.apiKeyEnc() == null || stored.apiKeyEnc().isEmpty()) {
			return Resolved.none();
		}
		String key;
		try {
			key = secrets.decrypt(stored.apiKeyEnc());
		}
		catch (RuntimeException e) {
			return Resolved.none();
		}
		return new Resolved(normalizeBaseUrl(stored.baseUrl()), key, stored.model(), true);
	}

	public static String normalizeBaseUrl(String raw) {
		String value = raw == null ? "" : raw.trim();
		int q = value.indexOf('?');
		if (q >= 0) {
			value = value.substring(0, q);
		}
		value = stripTrailingSlashes(value);
		String suffix = "/chat/completions";
		String stripped = stripTrailingSlashes(value);
		String suffixStripped = stripTrailingSlashes(suffix);
		if (stripped.equalsIgnoreCase(suffixStripped)) {
			return "";
		}
		if (value.length() >= suffix.length()
				&& value.regionMatches(true, value.length() - suffix.length(), suffix, 0, suffix.length())) {
			value = stripTrailingSlashes(value.substring(0, value.length() - suffix.length()));
		}
		return value;
	}

	private static String stripTrailingSlashes(String value) {
		int end = value.length();
		while (end > 0 && value.charAt(end - 1) == '/') {
			end--;
		}
		return value.substring(0, end);
	}

}