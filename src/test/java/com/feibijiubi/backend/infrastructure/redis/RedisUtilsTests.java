package com.feibijiubi.backend.infrastructure.redis;

import com.feibijiubi.backend.shared.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    // ------------------------------------------------- pipeline EXISTS 批量查询

    @Test
    void hasKeysUsesOnePipelineAndKeepsPerKeyOrder() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(call -> {
            RedisCallback<?> callback = call.getArgument(0);
            // 回调自身不得直接返回业务结果，否则 Spring 会报错；结果来自 pipeline 关闭。
            assertNull(callback.doInRedis(connection));
            return results(true, false, true);
        });

        List<Boolean> exists = redisUtils.hasKeys(
                List.of("video:status:process:v1:a",
                        "video:status:process:v1:b",
                        "video:status:process:v1:c")
        );

        assertEquals(List.of(true, false, true), exists);
        // 三个 key 一次 pipeline，不是三次独立 RTT。
        verify(redisTemplate, times(1)).executePipelined(any(RedisCallback.class));
        verify(keyCommands, times(3)).exists(any(byte[].class));
    }

    @Test
    void hasKeysSkipsRedisForEmptyInput() {
        assertTrue(redisUtils.hasKeys(List.of()).isEmpty());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void hasKeysAcceptsNumericRepliesFromOtherClients() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(results(1L, 0L));

        assertEquals(List.of(true, false),
                redisUtils.hasKeys(List.of("k1", "k2")));
    }

    @Test
    void hasKeysAcceptsOnlyIntegralZeroAndOne() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(results(true, false, 1L, 0L, 1, 0, (short) 1, (byte) 0));

        assertEquals(
                List.of(true, false, true, false, true, false, true, false),
                redisUtils.hasKeys(
                        List.of("k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8"))
        );
    }

    @Test
    void hasKeysFailsClosedOnFractionalReply() {
        // 0.5/1.9 的 longValue() 会是 0/1，旧的宽松实现会误把它当成“不存在/存在”。
        assertReplyRejected(0.5d);
        assertReplyRejected(1.9d);
        // 整数值的浮点同样不是 Redis EXISTS 的真实回复类型。
        assertReplyRejected(0.0d);
        assertReplyRejected(1.0d);
        assertReplyRejected(new BigDecimal("0.5"));
        assertReplyRejected(new BigDecimal("1.9"));
    }

    @Test
    void hasKeysFailsClosedOnNanReply() {
        // NaN.longValue() == 0，宽松实现会静默当成“不存在”。
        assertReplyRejected(Double.NaN);
        assertReplyRejected(Float.NaN);
    }

    @Test
    void hasKeysFailsClosedOnTruncatingIntegerReply() {
        BigInteger overflowToZero = BigInteger.valueOf(2).pow(64);
        BigInteger overflowToOne = overflowToZero.add(BigInteger.ONE);
        assertReplyRejected(overflowToZero);
        assertReplyRejected(overflowToOne);
        assertReplyRejected(new BigDecimal(overflowToOne));
        assertReplyRejected(Long.MAX_VALUE);
        assertReplyRejected(Integer.MIN_VALUE);
    }

    @Test
    void hasKeysFailsClosedOnNullPipelineResult() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(null);

        assertThrows(RedisOperationException.class,
                () -> redisUtils.hasKeys(List.of("k1")));
    }

    @Test
    void hasKeysFailsClosedOnResultLengthMismatch() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(results(true));

        assertThrows(RedisOperationException.class,
                () -> redisUtils.hasKeys(List.of("k1", "k2")));
    }

    @Test
    void hasKeysFailsClosedOnNullEntry() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(results(true, null));

        assertThrows(RedisOperationException.class,
                () -> redisUtils.hasKeys(List.of("k1", "k2")));
    }

    @Test
    void hasKeysFailsClosedOnUnknownReplyType() {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(results("OK"));

        assertThrows(RedisOperationException.class,
                () -> redisUtils.hasKeys(List.of("k1")));
    }

    @Test
    void hasKeysConvertsPipelineFailureToRedisOperationException() {
        DataAccessResourceFailureException cause =
                new DataAccessResourceFailureException("connection failed");
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenThrow(cause);

        RedisOperationException exception = assertThrows(
                RedisOperationException.class,
                () -> redisUtils.hasKeys(List.of("k1"))
        );

        assertSame(cause, exception.getCause());
    }

    private void assertReplyRejected(Object reply) {
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenReturn(results(reply));

        RedisOperationException exception = assertThrows(
                RedisOperationException.class,
                () -> redisUtils.hasKeys(List.of("k1")),
                "应当拒绝的 pipeline EXISTS 回复: " + reply
        );

        assertTrue(exception.getMessage().contains("无法识别"));
    }

    private static List<Object> results(Object... values) {
        return Arrays.asList(values);
    }
}
