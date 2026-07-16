package com.feibijiubi.backend.utils.redis;

import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.utils.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisUtilsTests {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private JsonUtils jsonUtils;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisUtils redisUtils;

    @BeforeEach
    void setUp() {
        redisUtils = new RedisUtils(redisTemplate, jsonUtils);
    }

    @Test
    void convertsReadFailureToRedisOperationException() {
        DataAccessResourceFailureException cause =
                new DataAccessResourceFailureException("connection failed");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("key")).thenThrow(cause);

        RedisOperationException exception = assertThrows(
                RedisOperationException.class,
                () -> redisUtils.getString("key")
        );

        assertSame(cause, exception.getCause());
    }

    @Test
    void convertsScriptFailureToRedisOperationException() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        DataAccessResourceFailureException cause =
                new DataAccessResourceFailureException("connection failed");
        when(redisTemplate.execute(
                eq(script),
                eq(Collections.singletonList("key")),
                any(Object[].class)
        )).thenThrow(cause);

        RedisOperationException exception = assertThrows(
                RedisOperationException.class,
                () -> redisUtils.executeScript(
                        script,
                        Collections.singletonList("key"),
                        "60"
                )
        );

        assertSame(cause, exception.getCause());
    }
}
