package com.clouddrive.file;

import java.util.List;

/**
 * 文件夹树端口，对应 Go file.FolderTree。
 */
public interface FolderTree {

	/** 返回文件夹 owner_id。 */
	long findFolderOwner(long folderId);

	List<Long> descendantIds(long folderId);

}