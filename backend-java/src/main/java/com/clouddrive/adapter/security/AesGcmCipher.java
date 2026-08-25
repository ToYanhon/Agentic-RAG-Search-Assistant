package com.clouddrive.adapter.security;

import com.clouddrive.llmconfig.Secret;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 密钥加密，对应 Go security.AesGCMSecret。 密文格式 v1:base64(nonce):base64(sealed)，主密钥
 * sha256 派生，12 字节随机 nonce。
 */
public class AesGcmCipher implements Secret {

	private static final int NONCE_SIZE = 12;

	private static final String VERSION = "v1";

	private final byte[] key;

	private final SecureRandom random = new SecureRandom();

	public AesGcmCipher(String master) {
		if (master == null || master.length() < 32) {
			throw new IllegalArgumentException("llm encryption key must be at least 32 bytes");
		}
		this.key = sha256(master);
	}

	@Override
	public String encrypt(String plain) {
		try {
			byte[] nonce = new byte[NONCE_SIZE];
			random.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
			byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return VERSION + ":" + Base64.getEncoder().encodeToString(nonce) + ":"
					+ Base64.getEncoder().encodeToString(sealed);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("encrypt failed", e);
		}
	}

	@Override
	public String decrypt(String encoded) {
		try {
			String[] parts = encoded.split(":", -1);
			if (parts.length != 3 || !VERSION.equals(parts[0])) {
				throw new IllegalArgumentException("invalid ciphertext");
			}
			byte[] nonce = Base64.getDecoder().decode(parts[1]);
			byte[] sealed = Base64.getDecoder().decode(parts[2]);
			if (nonce.length != NONCE_SIZE) {
				throw new IllegalArgumentException("invalid nonce");
			}
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
			return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalArgumentException("decrypt failed", e);
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