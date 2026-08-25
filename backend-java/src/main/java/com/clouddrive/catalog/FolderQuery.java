package com.clouddrive.catalog;

import java.util.List;

/**
 * 文件夹查询端口，对应 Go catalog.FolderQuery。
 */
public interface FolderQuery {

	Folder findFolder(long id);

	List<Folder> listRootFolders(long ownerId);

	List<Folder> listChildFolders(long ownerId, long parentId);

}