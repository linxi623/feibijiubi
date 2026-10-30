package com.feibijiubi.backend.auth.service.impl;

import com.feibijiubi.backend.auth.service.TokenContext;
import com.feibijiubi.backend.auth.service.TokenService;
import com.feibijiubi.backend.service.ratelimit.RateLimitService;
import com.feibijiubi.backend.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Date;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceImplTests {
    @Mock
    private UserMapper userMapper;
    @Mock
    private TokenService tokenService;
    @Mock
    private RateLimitService rateLimitService;

    private UserAccountServiceImpl userAccountService;

    @BeforeEach
    void setUp() {
        userAccountService = new UserAccountServiceImpl(userMapper, tokenService, rateLimitService);
    }

    @Test
    void logoutBlacklistsCurrentToken() {
        TokenContext context = tokenContext();
        when(tokenService.isBlacklisted("jti-1")).thenReturn(false);

        userAccountService.logout(context);

        verify(tokenService).blacklist(eq("jti-1"), org.mockito.ArgumentMatchers.any(Duration.class));
    }

    @Test
    void repeatedLogoutIsIdempotent() {
        TokenContext context = tokenContext();
        when(tokenService.isBlacklisted("jti-1")).thenReturn(true);

        userAccountService.logout(context);

        verify(tokenService, never()).blacklist(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class)
        );
    }

    private TokenContext tokenContext() {
        return new TokenContext(
                "token-value",
                1,
                (byte) 0,
                "jti-1",
                new Date(),
                new Date(System.currentTimeMillis() + 60_000),
                0
        );
    }
}
