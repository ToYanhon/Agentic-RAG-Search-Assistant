package com.clouddrive.auth;

import java.time.Duration;

/**
 * JWT 黑名单端口，对应 Go auth.Blacklist。
 */
public interface Blacklist {

	void add(String jti, Duration ttl);

	boolean contains(String jti);

}