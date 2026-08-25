package com.clouddrive.repository;

import com.clouddrive.repository.entity.LlmConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface LlmConfigJpa extends JpaRepository<LlmConfigEntity, Long> {

	Optional<LlmConfigEntity> findByUserIdAndProvider(Long userId, String provider);

	List<LlmConfigEntity> findByUserIdOrderByProvider(Long userId);

	@Modifying
	@Transactional
	@Query(value = "INSERT INTO llm_configs (user_id,provider,base_url,api_key_enc,model,updated_at) "
			+ "VALUES (?1,?2,?3,?4,?5,NOW()) "
			+ "ON DUPLICATE KEY UPDATE base_url=VALUES(base_url),api_key_enc=VALUES(api_key_enc),model=VALUES(model),updated_at=NOW()",
			nativeQuery = true)
	int upsert(long userId, String provider, String baseUrl, String apiKeyEnc, String model);

	@Transactional
	void deleteByUserIdAndProvider(Long userId, String provider);

}