package com.clouddrive.file;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 对象 key 生成器，对应 Go file.RandomKey：users/{owner}/{nanoTime}-{32hex}。
 */
@org.springframework.stereotype.Component
public class RandomKey implements KeyGenerator {

	private static final SecureRandom RANDOM = new SecureRandom();

	@Override
	public String newKey(long ownerId) {
		byte[] buf = new byte[16];
		RANDOM.nextBytes(buf);
		return "users/" + ownerId + "/" + Instant.now().toEpochMilli() * 1_000_000L + System.nanoTime() % 1_000_000L
				+ "-" + HexFormat.of().formatHex(buf);
	}

}