package com.clouddrive.multipart;

/**
 * multipart 元数据，对应 Go multipart.Meta。
 */
public record Meta(long ownerId, String name, long size, String mimeType, Long folderId, String md5, long chunkSize,
		int totalChunks, String objectKey, String uploadId, long remaining) {
}