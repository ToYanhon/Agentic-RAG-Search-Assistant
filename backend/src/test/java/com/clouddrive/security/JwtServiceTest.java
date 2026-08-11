package com.clouddrive.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.clouddrive.config.AppProperties;

import io.jsonwebtoken.JwtException;

/**
 * JwtService 签发/解析测试（HS256，SHA-256 派生密钥）。
 */
class JwtServiceTest {

    private JwtService newService() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret("change-me-in-production");
        props.getJwt().setExpireHours(72);
        return new JwtService(props);
    }

    @Test
    void createAndParseRoundTrip() {
        JwtService jwt = newService();
        String token = jwt.create(42L, "alice");
        JwtService.TokenData td = jwt.parse(token);
        assertThat(td.userId()).isEqualTo(42L);
        assertThat(td.username()).isEqualTo("alice");
        assertThat(td.jti()).isNotBlank();
        assertThat(td.expiresAtSeconds()).isGreaterThan(System.currentTimeMillis() / 1000);
    }

    @Test
    void uniqueJtiPerToken() {
        JwtService jwt = newService();
        String t1 = jwt.create(1L, "a");
        String t2 = jwt.create(1L, "a");
        assertThat(jwt.parse(t1).jti()).isNotEqualTo(jwt.parse(t2).jti());
    }

    @Test
    void tamperedTokenRejected() {
        JwtService jwt = newService();
        String token = jwt.create(1L, "alice");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenFromDifferentSecretRejected() {
        JwtService jwt = newService();
        String token = jwt.create(1L, "alice");

        AppProperties other = new AppProperties();
        other.getJwt().setSecret("another-secret-value-here");
        JwtService otherJwt = new JwtService(other);
        assertThatThrownBy(() -> otherJwt.parse(token)).isInstanceOf(JwtException.class);
    }
}
