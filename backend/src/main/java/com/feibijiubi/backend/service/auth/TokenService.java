package com.feibijiubi.backend.service.auth;

import com.feibijiubi.backend.entity.User;

import java.time.Duration;

public interface TokenService {
    String createToken(User user);

    TokenContext parseToken(String token);

    void blacklist(String jti, Duration ttl);

    boolean isBlacklisted(String jti);
}
