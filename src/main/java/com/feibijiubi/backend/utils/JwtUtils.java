package com.feibijiubi.backend.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class JwtUtils {
    private JwtUtils() {}

    public static String createToken(
            Long userId,
            String username,
            Byte role,
            String secret,
            Long expireMinutes) {
        Date now = new Date();
        Date expireTime = new Date(now.getTime() + expireMinutes * 60 * 1000);
        SecretKey secretKey = createSecretKey(secret);

        return Jwts.builder()
                .claim("userId", userId)
                .claim("username", username)
                .claim("role", role)
                .setIssuedAt(now)
                .setExpiration(expireTime)
                .signWith(secretKey)
                .compact();
    }

    public static Claims parseToken(String token, String secret) {
        SecretKey secretKey = createSecretKey(secret);

        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public static Long getUserId(String token, String secret) {
        Claims claims = parseToken(token, secret);
        Object userId = claims.get("userId");

        if (userId == null) {
            return null;
        }

        return Long.valueOf(userId.toString());
    }

    private static SecretKey createSecretKey(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
