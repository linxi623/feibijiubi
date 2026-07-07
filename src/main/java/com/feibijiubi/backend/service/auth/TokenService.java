package com.feibijiubi.backend.service.auth;

import com.feibijiubi.backend.entity.User;

public interface TokenService {
    String createToken(User user);

    Long getUserId(String token);
}
