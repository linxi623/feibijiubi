package com.feibijiubi.backend.service.impl.auth;

import com.feibijiubi.backend.config.JwtProperties;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.service.auth.TokenContext;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
class JwtTokenServiceImplTests {
    private static final String SECRET = "feibijiubi-test-secret-key-with-at-least-32-bytes";

    @Mock
    private RedisUtils redisUtils;

    private JwtTokenServiceImpl tokenService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setExpireMinutes(60L);
        tokenService = new JwtTokenServiceImpl(properties, redisUtils);
    }

    @Test
    void createsAndParsesCompleteTokenContext() {
        User user = new User();
        user.setId(1);
        user.setUsername("test-user");
        user.setRole((byte) 1);
        user.setTokenVersion(3);

        String token = tokenService.createToken(user);
        TokenContext context = tokenService.parseToken(token);

        assertEquals(1, context.userId());
        assertEquals((byte) 1, context.role());
        assertEquals(3, context.tokenVersion());
        assertNotNull(context.jti());
        assertNotNull(context.expireTime());
    }
}
