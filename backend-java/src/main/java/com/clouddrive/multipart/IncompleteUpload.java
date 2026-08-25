package com.clouddrive.multipart;

/**
 * 未完成 multipart 上传，对应 Go multipart.IncompleteUpload。
 */
public record IncompleteUpload(String objectKey, String uploadId) {
}