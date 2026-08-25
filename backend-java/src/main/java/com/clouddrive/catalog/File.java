package com.clouddrive.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 文件目录项，对应 Go catalog.File。created_at 为 UTC "2006-01-02T15:04:05Z" 格式； folder_id 为 null
 * 表示根目录；owner_id 仅内部使用，不参与 JSON 序列化（对应 Go json:"-"）。
 */
public record File(long id, String name, long size, String mimeType, String md5, Long folderId,
		@JsonIgnore long ownerId, String createdAt) {
}