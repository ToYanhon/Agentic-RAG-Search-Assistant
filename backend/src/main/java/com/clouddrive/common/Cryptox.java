package com.clouddrive.common;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 对称加解密（对齐 Go pkg/cryptox，密文格式 v1:base64(nonce):base64(cipher)，
 * 12 字节 nonce、128 位 GCM tag）。格式一致保证能解密 Go 后端已落盘的存量密文。
 */
public final class Cryptox {

    private static final String VERSION = "v1";
    private static final String PREFIX = VERSION + ":";
    private static final int NONCE_BYTES = 12;

    private Cryptox() {
    }

    public static String encrypt(byte[] key, byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] sealed = cipher.doFinal(plaintext);
            return PREFIX + Base64.getEncoder().encodeToString(nonce) + ":"
                    + Base64.getEncoder().encodeToString(sealed);
        } catch (Exception e) {
            throw new IllegalStateException("cryptox encrypt failed", e);
        }
    }

    public static byte[] decrypt(byte[] key, String encoded) {
        try {
            String[] parts = encoded.split(":", 3);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("invalid ciphertext");
            }
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            byte[] sealed = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, nonce));
            return cipher.doFinal(sealed);
        } catch (Exception e) {
            throw new IllegalArgumentException("cryptox decrypt failed", e);
        }
    }
}
