package com.clouddrive.share;

import java.time.LocalDateTime;

/**
 * 分享仓储端口，对应 Go share.Repository。
 */
public interface Repository {

	Record create(long ownerId, long fileId, String token, LocalDateTime expiresAt);

	Record findByToken(String token);

	Record findOwned(long id, long ownerId);

	void delete(long id);

}