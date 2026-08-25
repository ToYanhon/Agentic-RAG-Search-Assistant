package com.clouddrive.file;

import java.time.LocalDateTime;

/**
 * 文件记录（含对象 key），对应 Go file.Record。
 */
public record Record(long id, long ownerId, Long folderId, String name, long size, String mimeType, String md5,
		String objectKey, LocalDateTime createdAt) {
}