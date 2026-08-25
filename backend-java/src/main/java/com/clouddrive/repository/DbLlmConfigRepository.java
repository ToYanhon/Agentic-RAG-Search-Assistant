package com.clouddrive.repository;

import com.clouddrive.common.Errors;
import com.clouddrive.llmconfig.Repository;
import com.clouddrive.llmconfig.Stored;
import com.clouddrive.repository.entity.LlmConfigEntity;

import java.util.List;

/**
 * LLM 配置仓储 MySQL 实现，对应 Go db.LLMConfigRepository。
 */
@org.springframework.stereotype.Repository
public class DbLlmConfigRepository implements Repository {

	private final LlmConfigJpa jpa;

	public DbLlmConfigRepository(LlmConfigJpa jpa) {
		this.jpa = jpa;
	}

	@Override
	public Stored find(long userId, String provider) {
		return jpa.findByUserIdAndProvider(userId, provider)
			.map(DbLlmConfigRepository::toStored)
			.orElseThrow(() -> new Errors.LlmConfigNotFound("llm config not found"));
	}

	@Override
	public List<Stored> findAll(long userId) {
		return jpa.findByUserIdOrderByProvider(userId).stream().map(DbLlmConfigRepository::toStored).toList();
	}

	@Override
	public void upsert(long userId, String provider, String baseUrl, String apiKeyEnc, String model) {
		jpa.upsert(userId, provider, baseUrl, apiKeyEnc, model);
	}

	@Override
	public void delete(long userId, String provider) {
		jpa.deleteByUserIdAndProvider(userId, provider);
	}

	private static Stored toStored(LlmConfigEntity e) {
		return new Stored(e.getProvider(), e.getBaseUrl(), e.getApiKeyEnc(), e.getModel(), e.getUpdatedAt());
	}

}