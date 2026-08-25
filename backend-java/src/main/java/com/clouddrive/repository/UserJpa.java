package com.clouddrive.repository;

import com.clouddrive.repository.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface UserJpa extends JpaRepository<UserEntity, Long> {

	Optional<UserEntity> findByUsername(String username);

	Optional<UserEntity> findByEmail(String email);

	@Modifying
	@Transactional
	@Query("UPDATE UserEntity u SET u.username = :username, u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :id")
	int updateUsername(@Param("id") Long id, @Param("username") String username);

	@Modifying
	@Transactional
	@Query("UPDATE UserEntity u SET u.password = :password, u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :id")
	int updatePassword(@Param("id") Long id, @Param("password") String password);

	@Query(value = "SELECT storage_used, storage_limit FROM users WHERE id = ?1", nativeQuery = true)
	List<Object[]> findStorage(Long id);

}