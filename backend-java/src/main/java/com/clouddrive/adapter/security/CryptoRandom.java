package com.clouddrive.adapter.security;

import com.clouddrive.auth.RandomHex;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 加密随机 hex 生成器，对应 Go security.CryptoRandom。
 */
public class CryptoRandom implements RandomHex {

	private static final SecureRandom RANDOM = new SecureRandom();

	@Override
	public String generate(int bytes) {
		byte[] value = new byte[bytes];
		RANDOM.nextBytes(value);
		return HexFormat.of().formatHex(value);
	}

}