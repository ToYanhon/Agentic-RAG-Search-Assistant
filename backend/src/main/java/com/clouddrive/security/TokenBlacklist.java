package com.clouddrive.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * JWT 黑名单：登出后把 jti 写入 Redis，TTL = 剩余有效期（对齐 Go tokenblacklist）。
 */
@Component
public class TokenBlacklist {

    private static final String PREFIX = "jti_blacklist:";

    private final StringRedisTemplate redis;

    public TokenBlacklist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void add(String jti, long expiresAtSeconds) {
        if (jti == null || jti.isEmpty()) {
            return;
        }
        long ttl = expiresAtSeconds - Instant.now().getEpochSecond();
        if (ttl <= 0) {
            ttl = 1;
        }
        redis.opsForValue().set(PREFIX + jti, "1", Duration.ofSeconds(ttl));
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti));
    }
}
