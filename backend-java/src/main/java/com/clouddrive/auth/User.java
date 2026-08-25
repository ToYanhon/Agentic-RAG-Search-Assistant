package com.clouddrive.auth;

import java.time.LocalDateTime;

/**
 * 用户模型，对应 Go auth.User。
 */
public record User(long id, String username, String email, String password, long storageUsed, long storageLimit,
		LocalDateTime createdAt) {

	public static User missing() {
		return new User(0, "", "", "", 0, 0, null);
	}
}