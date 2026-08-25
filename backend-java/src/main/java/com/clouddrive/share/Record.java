package com.clouddrive.share;

import java.time.LocalDateTime;

/**
 * 分享记录，对应 Go share.Record。
 */
public record Record(long id, long fileId, long ownerId, String token, LocalDateTime expiresAt,
		LocalDateTime createdAt) {
}