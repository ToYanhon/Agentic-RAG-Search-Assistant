package com.clouddrive.file;

/**
 * 索引通知端口，对应 Go file.Notifier。
 */
public interface Notifier {

	void reindex(long fileId, long ownerId);

	void unindex(long fileId, long ownerId);

}