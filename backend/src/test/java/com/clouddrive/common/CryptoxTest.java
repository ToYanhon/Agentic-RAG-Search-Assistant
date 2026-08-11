package com.clouddrive.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * Cryptox AES-256-GCM 测试：加解密回环 + 密文格式 v1:nonce:cipher 与 Go 兼容。
 */
class CryptoxTest {

    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < KEY.length; i++) {
            KEY[i] = (byte) i;
        }
    }

    @Test
    void roundTrip() {
        String cipher = Cryptox.encrypt(KEY, "sk-test-123".getBytes(StandardCharsets.UTF_8));
        assertThat(cipher).startsWith("v1:");
        assertThat(new String(Cryptox.decrypt(KEY, cipher), StandardCharsets.UTF_8))
                .isEqualTo("sk-test-123");
    }

    @Test
    void roundTripWithSha256DerivedKey() throws Exception {
        // 与 LLMConfigService 相同：主密钥字符串 → SHA-256 派生 32B
        byte[] key = java.security.MessageDigest.getInstance("SHA-256")
                .digest("dev-only-llm-encryption-key-change-me-32b".getBytes(StandardCharsets.UTF_8));
        String cipher = Cryptox.encrypt(key, "sk-secret-9999".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(Cryptox.decrypt(key, cipher), StandardCharsets.UTF_8))
                .isEqualTo("sk-secret-9999");
    }

    @Test
    void formatHasThreeParts() {
        String cipher = Cryptox.encrypt(KEY, "abc".getBytes(StandardCharsets.UTF_8));
        String[] parts = cipher.split(":", 3);
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("v1");
        // nonce 12 字节 base64 = 16 chars
        assertThat(Base64.getDecoder().decode(parts[1])).hasSize(12);
        assertThat(Base64.getDecoder().decode(parts[2])).isNotEmpty();
    }

    @Test
    void differentNoncePerEncrypt() {
        String a = Cryptox.encrypt(KEY, "x".getBytes(StandardCharsets.UTF_8));
        String b = Cryptox.encrypt(KEY, "x".getBytes(StandardCharsets.UTF_8));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void wrongKeyFails() {
        byte[] other = new byte[32];
        String cipher = Cryptox.encrypt(KEY, "secret".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Cryptox.decrypt(other, cipher))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidCiphertextRejected() {
        assertThatThrownBy(() -> Cryptox.decrypt(KEY, "v1:bad:bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cryptox.decrypt(KEY, "not-valid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
