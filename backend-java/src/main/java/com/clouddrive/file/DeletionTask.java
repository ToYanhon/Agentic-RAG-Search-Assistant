package com.clouddrive.file;

/**
 * 对象删除任务，对应 Go file.DeletionTask。
 */
public record DeletionTask(long id, String objectKey) {
}