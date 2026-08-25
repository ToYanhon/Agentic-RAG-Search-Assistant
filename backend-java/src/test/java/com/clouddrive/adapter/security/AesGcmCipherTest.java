package com.clouddrive.adapter.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmCipherTest {

	private static final String MASTER = "0123456789abcdef0123456789abcdef";

	@Test
	void encryptDecryptRoundTrip() {
		AesGcmCipher cipher = new AesGcmCipher(MASTER);
		String encoded = cipher.encrypt("sk-secret-123");
		assertTrue(encoded.startsWith("v1:"));
		assertEquals("sk-secret-123", cipher.decrypt(encoded));
	}

	@Test
	void randomNonceProducesDifferentCiphertext() {
		AesGcmCipher cipher = new AesGcmCipher(MASTER);
		assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"));
	}

	@Test
	void rejectsShortMasterKey() {
		assertThrows(IllegalArgumentException.class, () -> new AesGcmCipher("too-short"));
	}

	@Test
	void rejectsTamperedCiphertext() {
		AesGcmCipher cipher = new AesGcmCipher(MASTER);
		String encoded = cipher.encrypt("secret");
		String tampered = encoded.substring(0, encoded.length() - 4) + "aaaa";
		assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(tampered));
	}

}