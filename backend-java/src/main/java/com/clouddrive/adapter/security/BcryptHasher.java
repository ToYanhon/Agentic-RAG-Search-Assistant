package com.clouddrive.adapter.security;

import com.clouddrive.auth.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt 密码哈希，对应 Go security.BCryptHasher（DefaultCost=10）。
 */
public class BcryptHasher implements PasswordHasher {

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

	@Override
	public String hash(String password) {
		return encoder.encode(password);
	}

	@Override
	public boolean matches(String password, String hashed) {
		return encoder.matches(password, hashed);
	}

}