package com.clouddrive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Redis 连接工厂：解析 CD_REDIS_ADDR（host:port）与 CD_REDIS_PASSWORD，与 Go 后端配置契约一致。
 */
@Configuration
public class RedisConfig {

	@Bean
	public RedisConnectionFactory redisConnectionFactory(AppProperties properties) {
		String addr = properties.getRedisAddr();
		if (addr == null || addr.isBlank()) {
			addr = "localhost:6379";
		}
		int colon = addr.lastIndexOf(':');
		String host = colon > 0 ? addr.substring(0, colon) : addr;
		int port = colon > 0 ? parseIntOr(addr.substring(colon + 1), 6379) : 6379;
		RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
		if (properties.getRedisPassword() != null && !properties.getRedisPassword().isEmpty()) {
			config.setPassword(properties.getRedisPassword());
		}
		return new LettuceConnectionFactory(config);
	}

	private static int parseIntOr(String value, int fallback) {
		try {
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e) {
			return fallback;
		}
	}

}
