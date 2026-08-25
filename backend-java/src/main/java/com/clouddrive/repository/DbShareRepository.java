package com.clouddrive.repository;

import com.clouddrive.common.Errors;
import com.clouddrive.repository.entity.ShareEntity;
import com.clouddrive.share.Record;
import com.clouddrive.share.Repository;

import java.time.LocalDateTime;

/**
 * 分享仓储 MySQL 实现，对应 Go db.ShareRepository。
 */
@org.springframework.stereotype.Repository
public class DbShareRepository implements Repository {

	private final ShareJpa jpa;

	public DbShareRepository(ShareJpa jpa) {
		this.jpa = jpa;
	}

	@Override
	public Record create(long ownerId, long fileId, String token, LocalDateTime expiresAt) {
		ShareEntity entity = new ShareEntity();
		entity.setOwnerId(ownerId);
		entity.setFileId(fileId);
		entity.setToken(token);
		entity.setExpiredAt(expiresAt);
		entity.setCreatedAt(LocalDateTime.now());
		ShareEntity saved = jpa.save(entity);
		return new Record(saved.getId(), saved.getFileId(), saved.getOwnerId(), saved.getToken(), saved.getExpiredAt(),
				saved.getCreatedAt());
	}

	@Override
	public Record findByToken(String token) {
		return jpa.findByToken(token)
			.map(DbShareRepository::toRecord)
			.orElseThrow(() -> new Errors.ShareNotFound("share not found"));
	}

	@Override
	public Record findOwned(long id, long ownerId) {
		return jpa.findByIdAndOwnerId(id, ownerId)
			.map(DbShareRepository::toRecord)
			.orElseThrow(() -> new Errors.ShareNotFound("share not found"));
	}

	@Override
	public void delete(long id) {
		if (jpa.deleteShare(id) == 0) {
			throw new Errors.ShareNotFound("share not found");
		}
	}

	private static Record toRecord(ShareEntity e) {
		return new Record(e.getId(), e.getFileId(), e.getOwnerId(), e.getToken(), e.getExpiredAt(), e.getCreatedAt());
	}

}