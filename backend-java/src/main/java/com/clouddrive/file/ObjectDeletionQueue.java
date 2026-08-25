package com.clouddrive.file;

import java.util.List;

/**
 * 对象删除队列端口，对应 Go file.ObjectDeletionQueue。
 */
public interface ObjectDeletionQueue {

	void ensure();

	List<DeletionTask> pending(int limit);

	void complete(long id);

	void retry(long id, int increment);

}