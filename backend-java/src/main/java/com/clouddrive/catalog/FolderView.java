package com.clouddrive.catalog;

import java.util.List;

/**
 * 文件夹树视图，对应 Go catalog.FolderView。children 与 files 始终为数组。
 */
public record FolderView(long id, String name, Long parentId, String createdAt, List<FolderView> children,
		List<File> files) {

	public static FolderView leaf(Folder folder) {
		return new FolderView(folder.id(), folder.name(), folder.parentId(), "", List.of(), List.of());
	}
}