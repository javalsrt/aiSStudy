package com.znxsgl.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private static final int MIN_SECRET_BYTES = 32; // HS256 最少 256 位 = 32 字节

    private final SecretKey key;
    private final long expiration;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration:86400000}") long expiration) {
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length == 0) {
            throw new IllegalStateException(
                "未配置 JWT_SECRET：请在环境变量或服务器 .env 中设置至少 32 字节的随机密钥");
        }
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "JWT secret 长度不足，至少需要 " + MIN_SECRET_BYTES + " 字节（256 位），当前 " + secretBytes.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expiration = expiration;
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

    /**
     * 访客只读 token：以管理员视图浏览数据，带 guest 标记，有效期最长 2 小时。
     * JwtAuthFilter 会拦截该 token 的所有非 GET 请求。
     */
    public String generateGuestToken(Long adminId) {
        long guestExpiration = Math.min(expiration, 2 * 60 * 60 * 1000L);
        return Jwts.builder()
                .subject(adminId.toString())
                .claim("username", "guest")
                .claim("role", 3)
                .claim("guest", true)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + guestExpiration))
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
