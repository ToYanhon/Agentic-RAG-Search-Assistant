package com.clouddrive.adapter.redis;

import com.clouddrive.indexnotify.Queue;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 索引通知队列 Redis 实现，对应 Go adapter/redis.IndexNotifyQueue。key: task:index_notify（LPUSH/RPOP
 * FIFO）。
 */
@Component
public class RedisIndexNotifyQueue implements Queue {

	private static final String KEY = "task:index_notify";

	private final StringRedisTemplate redis;

	public RedisIndexNotifyQueue(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void push(String json) {
		redis.opsForList().leftPush(KEY, json);
	}

	@Override
	public String pop() {
		return redis.opsForList().rightPop(KEY);
	}

}