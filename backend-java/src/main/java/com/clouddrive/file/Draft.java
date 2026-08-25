package com.clouddrive.file;

/**
 * 新建文件草稿，对应 Go file.Draft。
 */
public record Draft(long ownerId, long size, Long folderId, String name, String mimeType, String md5,
		String objectKey) {
}