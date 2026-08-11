package com.feibijiubi.backend.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

public class JwtUtils {
    private JwtUtils() {}

    public static String createToken(
            Integer userId,
            String username,
            Byte role,
            Integer tokenVersion,
            String secret,
            Long expireMinutes) {
        Date now = new Date();
        Date expireTime = new Date(now.getTime() + expireMinutes * 60 * 1000);
        SecretKey secretKey = createSecretKey(secret);

        return Jwts.builder()
                .claim("userId", userId)
                .claim("username", username)
                .claim("role", role)
                .claim("tokenVersion", tokenVersion == null ? 0 : tokenVersion)
                .setIssuedAt(now)
                .setExpiration(expireTime)
                .signWith(secretKey)
                .setId(UUID.randomUUID().toString())
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

    public static Integer getTokenVersion(Claims claims) {
        Object tokenVersion = claims.get("tokenVersion");
        if (tokenVersion == null) {
            return 0;
        }
        return Integer.valueOf(tokenVersion.toString());
    }

    private static SecretKey createSecretKey(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
