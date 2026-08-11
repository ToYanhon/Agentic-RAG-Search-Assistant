package com.clouddrive.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.clouddrive.entity.Share;

/**
 * 分享数据访问（对齐 Go shareRepo）。
 */
public interface ShareRepository extends JpaRepository<Share, Long> {

    Optional<Share> findByToken(String token);

    Optional<Share> findByIdAndOwnerId(Long id, Long ownerId);
}
