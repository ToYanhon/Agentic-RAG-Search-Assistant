package com.clouddrive.catalog;

import java.util.List;

/**
 * 文件夹命令端口，对应 Go catalog.FolderCommand。
 */
public interface FolderCommand {

	Folder createFolder(long ownerId, Long parentId, String name);

	/** 返回是否实际变更（目标不存在或非本人时 false）。 */
	boolean renameFolder(long ownerId, long folderId, String name);

	boolean moveFolder(long ownerId, long folderId, Long parentId);

	List<Long> collectChildIds(long folderId);

}