package com.clouddrive.adapter.redis;

import com.clouddrive.auth.Blacklist;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * JWT 黑名单 Redis 实现，对应 Go adapter/redis.Blacklist。key: jti_blacklist:{jti}。
 */
@Component
public class RedisBlacklist implements Blacklist {

	private final StringRedisTemplate redis;

	public RedisBlacklist(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void add(String jti, Duration ttl) {
		redis.opsForValue().set("jti_blacklist:" + jti, "1", ttl);
	}

	@Override
	public boolean contains(String jti) {
		return Boolean.TRUE.equals(redis.hasKey("jti_blacklist:" + jti));
	}

}