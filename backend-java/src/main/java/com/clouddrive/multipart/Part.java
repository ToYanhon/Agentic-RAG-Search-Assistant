package com.clouddrive.multipart;

/**
 * multipart 分块，对应 Go multipart.Part（number 为 1 基）。
 */
public record Part(int number, String etag) {
}