package com.clouddrive.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 通用 Redis JSON 缓存（对齐 Go pkg/cache）：Get 命中返回 Optional；Redis 异常降级，
 * 调用方应回退数据库。
 */
@Component
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final RedisTemplate<String, Object> redis;

    public CacheService(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            Object value = redis.opsForValue().get(key);
            if (value == null) {
                return Optional.empty();
            }
            if (type.isInstance(value)) {
                return Optional.of((T) value);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("cache get failed: {}", key, e);
            return Optional.empty();
        }
    }

    public void set(String key, Object value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("cache set failed: {}", key, e);
        }
    }

    public void del(String... keys) {
        try {
            redis.delete(List.of(keys));
        } catch (Exception e) {
            log.warn("cache del failed: {}", String.join(",", keys), e);
        }
    }
}
