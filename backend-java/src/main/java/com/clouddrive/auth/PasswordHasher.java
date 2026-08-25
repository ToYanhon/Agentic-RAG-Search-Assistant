package com.clouddrive.auth;

/**
 * 密码哈希端口，对应 Go auth.PasswordHasher。
 */
public interface PasswordHasher {

	String hash(String password);

	boolean matches(String password, String hashed);

}