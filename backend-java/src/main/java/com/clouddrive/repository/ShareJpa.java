package com.clouddrive.repository;

import com.clouddrive.repository.entity.ShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface ShareJpa extends JpaRepository<ShareEntity, Long> {

	Optional<ShareEntity> findByToken(String token);

	Optional<ShareEntity> findByIdAndOwnerId(Long id, Long ownerId);

	@Modifying
	@Transactional
	@Query("DELETE FROM ShareEntity s WHERE s.id = ?1")
	int deleteShare(Long id);

}