package com.clouddrive.auth;

import java.time.Instant;

/**
 * JWT 解析结果，对应 Go auth.Claims。
 */
public record Claims(long userId, String username, String jti, Instant expiresAt) {
}