package com.clouddrive.multipart;

/**
 * 文件夹所属端口，对应 Go multipart.FolderOwner。
 */
public interface FolderOwner {

	long findFolder(long folderId);

}