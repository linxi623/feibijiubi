package com.feibijiubi.backend.auth.service;

import com.feibijiubi.backend.user.entity.User;

import java.time.Duration;

public interface TokenService {
    String createToken(User user);

    TokenContext parseToken(String token);

    void blacklist(String jti, Duration ttl);

    boolean isBlacklisted(String jti);
}
