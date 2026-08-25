package com.clouddrive.adapter.security;

import com.clouddrive.auth.Claims;
import com.clouddrive.auth.RandomHex;
import com.clouddrive.auth.TokenService;
import com.clouddrive.common.Errors;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * HS256 JWT，对应 Go security.HS256JWT。 签名密钥为 sha256(secret)；claims：user_id、username +
 * sub/jti/iat/exp；jti 为 16 字节 hex。
 */
public class Hs256JwtService implements TokenService {

	private final SecretKey key;

	private final Duration expiry;

	private final RandomHex random;

	public Hs256JwtService(String secret, Duration expiry, RandomHex random) {
		this.key = Keys.hmacShaKeyFor(sha256(secret));
		this.expiry = expiry;
		this.random = random;
	}

	@Override
	public String create(long userId, String username) {
		String jti = random.generate(16);
		Instant now = Instant.now();
		return Jwts.builder()
			.header()
			.type("JWT")
			.and()
			.claim("user_id", userId)
			.claim("username", username)
			.subject(username)
			.id(jti)
			.issuedAt(Date.from(now))
			.expiration(Date.from(now.plus(expiry)))
			.signWith(key, Jwts.SIG.HS256)
			.compact();
	}

	@Override
	public Claims parse(String raw) {
		try {
			io.jsonwebtoken.Claims payload = Jwts.parser().verifyWith(key).build().parseSignedClaims(raw).getPayload();
			Number userId = payload.get("user_id", Number.class);
			if (userId == null || userId.longValue() <= 0 || payload.getExpiration() == null) {
				throw new Errors.TokenInvalid("invalid or expired token");
			}
			return new Claims(userId.longValue(), payload.get("username", String.class), payload.getId(),
					payload.getExpiration().toInstant());
		}
		catch (JwtException | IllegalArgumentException e) {
			throw new Errors.TokenInvalid("invalid or expired token");
		}
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}