package com.clouddrive.adapter.redis;

import com.clouddrive.file.ChecksumCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 秒传校验缓存 Redis 实现。key: java:checksum:{owner}:{md5}，值 "true"/"false"。
 */
@Component
public class RedisChecksumCache implements ChecksumCache {

	private static final String PREFIX = "java:checksum:";

	private final StringRedisTemplate redis;

	public RedisChecksumCache(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public ChecksumResult get(long ownerId, String md5) {
		String value = redis.opsForValue().get(key(ownerId, md5));
		if (value == null) {
			return new ChecksumResult(false, false);
		}
		return new ChecksumResult(Boolean.parseBoolean(value), true);
	}

	@Override
	public void set(long ownerId, String md5, boolean exists, Duration ttl) {
		redis.opsForValue().set(key(ownerId, md5), Boolean.toString(exists), ttl);
	}

	@Override
	public void delete(long ownerId, String md5) {
		redis.delete(key(ownerId, md5));
	}

	private static String key(long ownerId, String md5) {
		return PREFIX + ownerId + ":" + md5;
	}

}