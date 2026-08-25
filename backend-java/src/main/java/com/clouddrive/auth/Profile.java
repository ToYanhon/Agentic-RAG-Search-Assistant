package com.clouddrive.auth;

/**
 * 用户资料响应，对应 Go auth.Profile。created_at 为 UTC "2006-01-02T15:04:05Z" 格式。
 */
public record Profile(long id, String username, String email, long storageUsed, long storageLimit, String createdAt) {
}