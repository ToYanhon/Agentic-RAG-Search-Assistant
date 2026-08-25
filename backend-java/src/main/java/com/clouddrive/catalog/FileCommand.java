package com.clouddrive.catalog;

/**
 * 文件命令端口，对应 Go catalog.FileCommand。
 */
public interface FileCommand {

	/** 返回指定 (owner, folder, name) 下是否存在同名文件（排除 excludeId）。 */
	boolean nameTaken(long ownerId, Long folderId, String name, long excludeId);

	boolean renameFile(long ownerId, long fileId, String name);

	boolean moveFile(long ownerId, long fileId, Long folderId, String name);

}