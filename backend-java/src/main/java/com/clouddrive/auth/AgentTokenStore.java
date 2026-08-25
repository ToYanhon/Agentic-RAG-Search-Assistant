package com.clouddrive.auth;

import java.time.Duration;

/**
 * Agent token 存储端口，对应 Go auth.AgentTokenStore。
 */
public interface AgentTokenStore {

	void save(String token, Duration ttl);

	String get();

}