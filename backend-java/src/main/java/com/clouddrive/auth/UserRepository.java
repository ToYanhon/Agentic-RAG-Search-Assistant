package com.clouddrive.auth;

/**
 * 用户仓储端口，对应 Go auth.UserRepository。
 */
public interface UserRepository {

	User findById(long id);

	User findByUsername(String username);

	User findByEmail(String email);

	void create(String username, String email, String password);

	void updateUsername(long id, String username);

	void updatePassword(long id, String password);

	/** 返回 (remaining, present)；用户不存在时 present=false。 */
	Quota remaining(long ownerId);

	record Quota(long remaining, boolean present) {
	}

}