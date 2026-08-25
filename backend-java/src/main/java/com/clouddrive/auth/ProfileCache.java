package com.clouddrive.auth;

import java.time.Duration;

/**
 * 资料缓存端口，对应 Go auth.ProfileCache。
 */
public interface ProfileCache {

	Profile get(long userId);

	boolean contains(long userId);

	void set(Profile profile, Duration ttl);

	void delete(long userId);

}