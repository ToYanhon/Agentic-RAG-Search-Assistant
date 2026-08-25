package com.clouddrive.file;

import java.util.List;

/**
 * 文件仓储端口，对应 Go file.Repository。
 */
public interface Repository {

	Record find(long id);

	Record findByMd5Owner(long ownerId, String md5);

	Record createWithQuota(Draft draft);

	/** delta = 新大小 - 旧大小；旧对象 key 写入删除 outbox。 */
	void updateContent(Record updated, long delta, String oldKey);

	void deleteWithQuota(Record record);

	List<Record> filesInFolders(List<Long> folderIds);

	void deleteFolderCascade(long ownerId, List<Long> folderIds, List<Record> files);

}