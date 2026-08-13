package com.clouddrive.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * agent → 后端 内部调用令牌（对齐 Go tokenmanager）：Redis 存动态 token，
 * 15 分钟轮换、30 分钟 TTL；后端代理与 Python agent 均依赖此契约。
 * 启动即轮换一次，随后定时轮换。
 */
@Component
public class AgentTokenManager {

    private static final Logger log = LoggerFactory.getLogger(AgentTokenManager.class);

    private static final String KEY = "internal:agent:token";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public AgentTokenManager(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @PostConstruct
    void rotateOnStartup() {
        rotate();
    }

    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 15 * 60 * 1000)
    public void rotate() {
        try {
            byte[] b = new byte[32];
            random.nextBytes(b);
            redis.opsForValue().set(KEY, HexFormat.of().formatHex(b), TTL);
            log.info("agent token rotated");
        } catch (Exception e) {
            log.warn("agent token rotate failed", e);
        }
    }

    public boolean validate(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        try {
            String stored = readCurrent();
            return token.equals(stored);
        } catch (Exception e) {
            log.warn("agent token validate failed", e);
            return false;
        }
    }

    /** 取当前生效的内部 token（供 AgentNotifier 等向后端/agent 直连时携带）。 */
    public String get() {
        try {
            return readCurrent();
        } catch (Exception e) {
            log.warn("agent token get failed", e);
            return null;
        }
    }

    private String readCurrent() {
        return redis.opsForValue().get(KEY);
    }
}
