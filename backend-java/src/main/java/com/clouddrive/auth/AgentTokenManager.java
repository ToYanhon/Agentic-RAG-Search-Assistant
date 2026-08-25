package com.clouddrive.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * Agent token 管理器，对应 Go auth.AgentTokenManager。 32 随机字节 hex（64 字符），TTL 30 分钟；启动时轮换一次，之后每
 * 15 分钟轮换。
 */
@org.springframework.stereotype.Service
public class AgentTokenManager {

	private final AgentTokenStore store;

	private final RandomHex random;

	public AgentTokenManager(AgentTokenStore store, RandomHex random) {
		this.store = store;
		this.random = random;
	}

	@PostConstruct
	public void rotateOnStartup() {
		try {
			rotate();
		}
		catch (RuntimeException e) {
			// 启动轮换失败仅记录，后续定时任务会重试
		}
	}

	public void rotate() {
		store.save(random.generate(32), Duration.ofMinutes(30));
	}

	public boolean validate(String value) {
		try {
			String stored = store.get();
			return stored != null && value != null && !value.isEmpty() && value.equals(stored);
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	/** 返回当前共享 agent token（可能为 null）。 */
	public String current() {
		return store.get();
	}

	@Scheduled(fixedDelay = 15 * 60 * 1000L, initialDelay = 15 * 60 * 1000L)
	public void rotateOnSchedule() {
		rotate();
	}

}