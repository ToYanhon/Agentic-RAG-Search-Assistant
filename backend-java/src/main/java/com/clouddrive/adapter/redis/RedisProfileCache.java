package com.clouddrive.adapter.redis;

import com.clouddrive.auth.Profile;
import com.clouddrive.auth.ProfileCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户资料缓存 Redis 实现。Java 自有 key 前缀 java:，避免与 Go 的 go: 格式冲突。
 */
@Component
public class RedisProfileCache implements ProfileCache {

	private static final String PREFIX = "java:user_profile:";

	private final StringRedisTemplate redis;

	private final ObjectMapper mapper;

	public RedisProfileCache(StringRedisTemplate redis, ObjectMapper mapper) {
		this.redis = redis;
		this.mapper = mapper;
	}

	@Override
	public Profile get(long userId) {
		String value = redis.opsForValue().get(key(userId));
		if (value == null) {
			return null;
		}
		try {
			return mapper.readValue(value, Profile.class);
		}
		catch (JsonProcessingException e) {
			return null;
		}
	}

	@Override
	public boolean contains(long userId) {
		return Boolean.TRUE.equals(redis.hasKey(key(userId)));
	}

	@Override
	public void set(Profile profile, Duration ttl) {
		try {
			redis.opsForValue().set(key(profile.id()), mapper.writeValueAsString(profile), ttl);
		}
		catch (JsonProcessingException ignored) {
			// 缓存写入失败不阻塞主流程
		}
	}

	@Override
	public void delete(long userId) {
		redis.delete(key(userId));
	}

	private static String key(long userId) {
		return PREFIX + userId;
	}

}