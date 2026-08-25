package com.clouddrive.indexnotify;

/**
 * 索引通知队列端口，对应 Go indexnotify.Queue（Redis List，LPUSH/RPOP）。
 */
public interface Queue {

	void push(String json);

	/** 返回 JSON 任务或 null（队列为空）。 */
	String pop();

}