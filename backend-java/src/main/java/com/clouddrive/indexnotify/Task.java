package com.clouddrive.indexnotify;

/**
 * 索引通知任务，对应 Go indexnotify.Task（JSON 序列化写入 Redis 队列）。
 */
public record Task(String type, long fileId, long ownerId, int attempts, long nextRetry) {
}