package com.clouddrive.llmconfig;

import java.util.List;

/**
 * LLM 配置仓储端口，对应 Go llmconfig.Repository。
 */
public interface Repository {

	Stored find(long userId, String provider);

	List<Stored> findAll(long userId);

	void upsert(long userId, String provider, String baseUrl, String apiKeyEnc, String model);

	void delete(long userId, String provider);

}