package com.feibijiubi.backend.service.impl.user;

import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.service.auth.TokenContext;
import com.feibijiubi.backend.service.auth.TokenService;
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

    private UserAccountServiceImpl userAccountService;

    @BeforeEach
    void setUp() {
        userAccountService = new UserAccountServiceImpl(userMapper, tokenService);
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
