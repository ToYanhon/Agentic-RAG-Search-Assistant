package com.clouddrive.adapter.redis;

import com.clouddrive.auth.AgentTokenStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Agent token 存储 Redis 实现，对应 Go adapter/redis.AgentTokenStore。key: internal:agent:token（与
 * Agent 共享）。
 */
@Component
public class RedisAgentTokenStore implements AgentTokenStore {

	private static final String KEY = "internal:agent:token";

	private final StringRedisTemplate redis;

	public RedisAgentTokenStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void save(String token, Duration ttl) {
		redis.opsForValue().set(KEY, token, ttl);
	}

	@Override
	public String get() {
		return redis.opsForValue().get(KEY);
	}

}