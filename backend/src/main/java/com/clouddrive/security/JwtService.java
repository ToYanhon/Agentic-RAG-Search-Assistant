package com.clouddrive.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.clouddrive.config.AppProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

/**
 * HS256 JWT 签发/解析，对齐 Go（golang-jwt/v5）claims（user_id/username/jti）。
 *
 * 密钥：Go 直接使用 secret 原始字节（可能 <32B，违反 RFC 7518 且 jjwt 会拒绝），
 * 此处用 SHA-256 派生 32 字节密钥，保证 HS256 合规。注意由此 Java 签发的 token
 * 无法在 Go 后端校验（反之亦然）；迁移期各自签发各自校验，切换后仅 Java 签发，无影响。
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();

    public JwtService(AppProperties props) {
        this.props = props;
        this.key = deriveKey(props.getJwt().getSecret());
    }

    private static SecretKey deriveKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "HmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 解析结果（expiresAtSeconds 秒级时间戳，供登出黑名单 TTL）。 */
    public record TokenData(Long userId, String username, String jti, long expiresAtSeconds) {
    }

    public String create(Long userId, String username) {
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofHours(props.getJwt().getExpireHours()));
        return Jwts.builder()
                .id(newJti())
                .subject(username)
                .claim("user_id", userId)
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                // 显式指定 HS256：Go 的 secret 可能 <32 字节，自动推断会按最小密钥长度拒绝；
                // 密钥保持原始字节与 Go（[]byte(secret)）一致，保证跨后端可互验。
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /** 解析并校验签名/有效期；无效或过期抛 {@link JwtException}。 */
    public TokenData parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        Number uid = claims.get("user_id", Number.class);
        Date exp = claims.getExpiration();
        return new TokenData(
                uid == null ? null : uid.longValue(),
                claims.get("username", String.class),
                claims.getId(),
                exp == null ? 0 : exp.getTime() / 1000);
    }

    /** 生成随机 JWT ID（16 字节 hex，用于登出黑名单）。 */
    private String newJti() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
