package com.clouddrive.adapter.redis;

import com.clouddrive.share.Cache;
import com.clouddrive.share.Record;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 分享缓存 Redis 实现。Java 自有 key 前缀 java:share:，避免与 Go 的 go: 格式冲突。
 */
@Component
public class RedisShareCache implements Cache {

	private static final String PREFIX = "java:share:";

	private final StringRedisTemplate redis;

	private final ObjectMapper mapper;

	public RedisShareCache(StringRedisTemplate redis, ObjectMapper mapper) {
		this.redis = redis;
		this.mapper = mapper;
	}

	@Override
	public Record get(String key) {
		String value = redis.opsForValue().get(PREFIX + key);
		if (value == null) {
			return null;
		}
		try {
			return mapper.readValue(value, Record.class);
		}
		catch (JsonProcessingException e) {
			return null;
		}
	}

	@Override
	public void set(String key, Record record, Duration ttl) {
		try {
			redis.opsForValue().set(PREFIX + key, mapper.writeValueAsString(record), ttl);
		}
		catch (JsonProcessingException ignored) {
			// 缓存写入失败不阻塞主流程
		}
	}

	@Override
	public void delete(String key) {
		redis.delete(PREFIX + key);
	}

}