package com.clouddrive.catalog;

import java.util.List;

/**
 * 文件查询端口，对应 Go catalog.FileQuery。
 */
public interface FileQuery {

	File findFile(long id);

	FileList listFiles(long ownerId, int page, int pageSize);

	FileList searchFiles(long ownerId, String term, int page, int pageSize);

	List<File> listFolderFiles(long ownerId, long folderId);

	record FileList(List<File> files, long total) {
	}

}