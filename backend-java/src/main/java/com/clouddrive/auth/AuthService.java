package com.clouddrive.auth;

import com.clouddrive.common.ApiException;
import com.clouddrive.common.Errors;
import com.clouddrive.common.TimeUtil;

import java.time.Duration;
import java.time.Instant;

/**
 * 认证用例，对应 Go
 * auth.Service。依赖端口：UserRepository/TokenService/Blacklist/ProfileCache/PasswordHasher。
 */
@org.springframework.stereotype.Service
public class AuthService {

	private final UserRepository users;

	private final TokenService tokens;

	private final Blacklist blacklist;

	private final ProfileCache cache;

	private final PasswordHasher passwords;

	public AuthService(UserRepository users, TokenService tokens, Blacklist blacklist, ProfileCache cache,
			PasswordHasher passwords) {
		this.users = users;
		this.tokens = tokens;
		this.blacklist = blacklist;
		this.cache = cache;
		this.passwords = passwords;
	}

	public void register(String username, String email, String password) {
		ensureUsernameFree(username);
		ensureEmailFree(email);
		String hash = passwords.hash(password);
		users.create(username, email, hash);
	}

	public LoginResult login(String username, String password) {
		User user;
		try {
			user = users.findByUsername(username);
		}
		catch (Errors.NotFound e) {
			throw new Errors.InvalidCredentials("invalid username or password");
		}
		if (!passwords.matches(password, user.password())) {
			throw new Errors.InvalidCredentials("invalid username or password");
		}
		String token = tokens.create(user.id(), user.username());
		return new LoginResult(token, profileOf(user));
	}

	public void logout(String raw) {
		Claims claims;
		try {
			claims = tokens.parse(raw);
		}
		catch (Errors.TokenInvalid e) {
			throw new Errors.TokenInvalid("unauthorized");
		}
		if (claims.jti() == null || claims.jti().isEmpty()) {
			throw new Errors.TokenInvalid("unauthorized");
		}
		long ttlMillis = Duration.between(Instant.now(), claims.expiresAt()).toMillis();
		if (ttlMillis <= 0) {
			ttlMillis = 1000;
		}
		blacklist.add(claims.jti(), Duration.ofMillis(ttlMillis));
	}

	public Claims authenticate(String raw) {
		Claims claims = tokens.parse(raw);
		if (blacklist.contains(claims.jti())) {
			throw new Errors.TokenInvalid("invalid or expired token");
		}
		return claims;
	}

	public Profile profile(long userId) {
		Profile cached = cache.get(userId);
		if (cached != null) {
			return cached;
		}
		Profile profile = profileOf(users.findById(userId));
		cache.set(profile, Duration.ofMinutes(5));
		return profile;
	}

	public Profile updateUsername(long userId, String username) {
		if (username == null || username.trim().isEmpty()) {
			throw ApiException.badRequest("nothing to update");
		}
		try {
			User existing = users.findByUsername(username);
			if (existing.id() != userId) {
				throw new Errors.UsernameTaken("username already exists");
			}
		}
		catch (Errors.NotFound ignored) {
			// 用户名空闲
		}
		users.updateUsername(userId, username);
		cache.delete(userId);
		return profile(userId);
	}

	public void changePassword(long userId, String oldPassword, String newPassword) {
		User user = users.findById(userId);
		if (!passwords.matches(oldPassword, user.password())) {
			throw new Errors.WrongPassword("wrong password");
		}
		users.updatePassword(userId, passwords.hash(newPassword));
		cache.delete(userId);
	}

	private void ensureUsernameFree(String username) {
		try {
			users.findByUsername(username);
			throw new Errors.UsernameTaken("username already exists");
		}
		catch (Errors.NotFound ignored) {
			// 用户名空闲
		}
	}

	private void ensureEmailFree(String email) {
		try {
			users.findByEmail(email);
			throw new Errors.EmailTaken("email already exists");
		}
		catch (Errors.NotFound ignored) {
			// 邮箱空闲
		}
	}

	private static Profile profileOf(User user) {
		return new Profile(user.id(), user.username(), user.email(), user.storageUsed(), user.storageLimit(),
				TimeUtil.formatLocal(user.createdAt()));
	}

	public record LoginResult(String token, Profile user) {
	}

}