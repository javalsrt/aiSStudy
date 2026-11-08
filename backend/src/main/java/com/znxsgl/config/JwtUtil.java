package com.znxsgl.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    private static final String DEFAULT_SECRET = "ZnxsglRAGLearningApp2026SecretKeyForJWTTokenGenerationMustBe256Bits";
    private static final int MIN_SECRET_BYTES = 32; // HS256 最少 256 位 = 32 字节

    private final SecretKey key;
    private final long expiration;
    private final String rawSecret;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration:86400000}") long expiration) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "JWT secret 长度不足，至少需要 " + MIN_SECRET_BYTES + " 字节（256 位），当前 " + secretBytes.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expiration = expiration;
        this.rawSecret = secret;
    }

    @PostConstruct
    public void warnIfDefaultSecret() {
        if (DEFAULT_SECRET.equals(rawSecret)) {
            log.warn("JWT 使用默认开发密钥（仅限本地，生产请注入 JWT_SECRET）");
        }
    }

    public String generateToken(Long userId, String username, Integer role) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Long getUserId(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    public Integer getRole(String token) {
        return parseToken(token).get("role", Integer.class);
    }
}
