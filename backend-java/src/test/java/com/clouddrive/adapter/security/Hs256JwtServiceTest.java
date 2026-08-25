package com.clouddrive.adapter.security;

import com.clouddrive.auth.Claims;
import com.clouddrive.common.Errors;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Hs256JwtServiceTest {

	private static final String FIXTURE_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
			+ ".eyJqdGkiOiIwMDExMjIzMzQ0NTU2Njc3ODg5OWFhYmJjY2RkZWVmZiIsInN1YiI6ImZpeHR1cmUtdXNlciIs"
			+ "InVzZXJfaWQiOjQyLCJ1c2VybmFtZSI6ImZpeHR1cmUtdXNlciIsImlhdCI6MTcwMDAwMDAwMCwiZXhwIjo0MTAyNDQ0ODAwfQ"
			+ ".OTmX7otbTYL8Omu3jQKHUlcZHuimZm6mjJSAGcAishA";

	@Test
	void createAndParseRoundTrip() {
		Hs256JwtService svc = new Hs256JwtService("test-secret-value", Duration.ofHours(72), new CryptoRandom());
		String token = svc.create(42, "alice");
		Claims claims = svc.parse(token);
		assertEquals(42, claims.userId());
		assertEquals("alice", claims.username());
		assertTrue(claims.jti() != null && !claims.jti().isEmpty());
		assertTrue(claims.expiresAt().isAfter(java.time.Instant.now()));
	}

	@Test
	void parseJavaFixtureToken() {
		// 与 backend/testdata/auth/java-jwt-fixture.json 字节级兼容（密钥 sha256 派生，claims 形状一致）
		Hs256JwtService svc = new Hs256JwtService("fixture-secret", Duration.ofHours(1), new CryptoRandom());
		Claims claims = svc.parse(FIXTURE_TOKEN);
		assertEquals(42, claims.userId());
		assertEquals("fixture-user", claims.username());
		assertEquals("00112233445566778899aabbccddeeff", claims.jti());
	}

	@Test
	void parseRejectsGarbage() {
		Hs256JwtService svc = new Hs256JwtService("test-secret-value", Duration.ofHours(72), new CryptoRandom());
		assertThrows(Errors.TokenInvalid.class, () -> svc.parse("not-a-token"));
	}

	@Test
	void parseRejectsWrongKey() {
		Hs256JwtService svc = new Hs256JwtService("test-secret-value", Duration.ofHours(72), new CryptoRandom());
		String token = svc.create(42, "alice");
		Hs256JwtService other = new Hs256JwtService("different-secret", Duration.ofHours(72), new CryptoRandom());
		assertThrows(Errors.TokenInvalid.class, () -> other.parse(token));
	}

}