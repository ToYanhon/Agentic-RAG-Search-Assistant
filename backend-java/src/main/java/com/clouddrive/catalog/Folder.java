package com.clouddrive.catalog;

import java.time.LocalDateTime;

/**
 * 文件夹模型，对应 Go catalog.Folder。
 */
public record Folder(long id, String name, Long parentId, long ownerId, LocalDateTime createdAt) {
}