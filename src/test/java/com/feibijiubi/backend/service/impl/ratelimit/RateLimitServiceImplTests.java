package com.feibijiubi.backend.service.impl.ratelimit;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.utils.redis.RedisConstants;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceImplTests {

    @Mock
    private DefaultRedisScript<Long> redisScript;
    @Mock
    private RedisUtils redisUtils;

    private RateLimitServiceImpl rateLimitService;

    @BeforeEach
    void setUp() {
        rateLimitService = new RateLimitServiceImpl(redisScript, redisUtils);
    }

    @Test
    void rejectsUploadRequestWithoutUser() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> rateLimitService.checkUploadTokenLimit(null)
        );

        assertEquals(401, exception.getCode());
        verify(redisUtils, never()).executeScript(any(), any(), any(Object[].class));
    }

    @Test
    void allowsUploadRequestAtLimit() {
        String key = RedisKeyUtils.rateUpload(1);
        when(redisUtils.executeScript(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(RedisConstants.RATE_UPLOAD_WINDOW_SECONDS)
        )).thenReturn(RedisConstants.RATE_UPLOAD_MAX_REQUESTS);

        assertDoesNotThrow(() -> rateLimitService.checkUploadTokenLimit(1));
    }

    @Test
    void rejectsUploadRequestAboveLimit() {
        String key = RedisKeyUtils.rateUpload(1);
        when(redisUtils.executeScript(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(RedisConstants.RATE_UPLOAD_WINDOW_SECONDS)
        )).thenReturn(RedisConstants.RATE_UPLOAD_MAX_REQUESTS + 1);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> rateLimitService.checkUploadTokenLimit(1)
        );

        assertEquals(429, exception.getCode());
    }

    @Test
    void rejectsLoginWhenFailureLimitIsReached() {
        String key = RedisKeyUtils.loginFailTimes("alice");
        when(redisUtils.getString(key)).thenReturn(
                String.valueOf(RedisConstants.LOGIN_FAIL_MAX_TIMES)
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> rateLimitService.checkLoginFailureLimit("alice")
        );

        assertEquals(429, exception.getCode());
    }

    @Test
    void recordsLoginFailureWithConfiguredWindow() {
        String key = RedisKeyUtils.loginFailTimes("alice");
        when(redisUtils.executeScript(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(RedisConstants.LOGIN_FAIL_EXPIRE_TIME)
        )).thenReturn(1L);

        rateLimitService.recordLoginFailure("alice");

        verify(redisUtils).executeScript(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(RedisConstants.LOGIN_FAIL_EXPIRE_TIME)
        );
    }

    @Test
    void rejectsNullScriptResult() {
        String key = RedisKeyUtils.loginFailTimes("alice");
        when(redisUtils.executeScript(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(RedisConstants.LOGIN_FAIL_EXPIRE_TIME)
        )).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> rateLimitService.recordLoginFailure("alice")
        );

        assertEquals(500, exception.getCode());
    }

    @Test
    void clearsLoginFailuresDirectly() {
        String key = RedisKeyUtils.loginFailTimes("alice");

        rateLimitService.clearLoginFailures("alice");

        verify(redisUtils).delete(key);
    }

    @Test
    void propagatesRedisOperationException() {
        String key = RedisKeyUtils.loginFailTimes("alice");
        RedisOperationException failure = new RedisOperationException(
                "Redis 操作失败",
                new RuntimeException("connection failed")
        );
        when(redisUtils.getString(key)).thenThrow(failure);

        RedisOperationException thrown = assertThrows(
                RedisOperationException.class,
                () -> rateLimitService.checkLoginFailureLimit("alice")
        );

        assertSame(failure, thrown);
    }
}
