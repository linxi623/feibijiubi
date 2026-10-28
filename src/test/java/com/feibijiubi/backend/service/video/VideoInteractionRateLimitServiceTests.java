package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.config.VideoInteractionProperties;
import com.feibijiubi.backend.service.impl.video.interaction.VideoInteractionRateLimitServiceImpl;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionAction;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T15 第二阶段 Redis 准入（固定窗口限流）的纯单元测试：不连 Redis。
 *
 * <p>覆盖两个维度（单动作 / 同一 actor 合计）、边界（第 N 次允许、第 N+1 次 429）、键名与窗口参数，
 * 以及 fail-closed：脚本返回 {@code null}、负数或直接抛异常时一律拒绝，<b>不</b>退化成放行。</p>
 */
class VideoInteractionRateLimitServiceTests {

    private static final String ACTOR_HASH =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    private RedisUtils redisUtils;
    private VideoInteractionProperties properties;
    private VideoInteractionRateLimitServiceImpl service;

    @BeforeEach
    void setUp() {
        redisUtils = mock(RedisUtils.class);
        properties = new VideoInteractionProperties();
        properties.setWindowSeconds(60);
        properties.setMaxRequestsPerAction(2);
        properties.setMaxRequestsPerActor(3);
        service = new VideoInteractionRateLimitServiceImpl(
                new DefaultRedisScript<>(), redisUtils, properties);
    }

    @Test
    void withinBothLimitsConsumesActionThenActorDimension() {
        when(redisUtils.executeScript(any(), anyList(), any()))
                .thenReturn(1L, 1L);

        assertDoesNotThrow(() -> service.check(ACTOR_HASH, VideoInteractionAction.PLAY));

        verify(redisUtils, times(2)).executeScript(any(), anyList(), any());
        verify(redisUtils).executeScript(any(),
                eq(List.of("rate:video-interaction:action:" + ACTOR_HASH + ":PLAY")), eq("60"));
        verify(redisUtils).executeScript(any(),
                eq(List.of("rate:video-interaction:actor:" + ACTOR_HASH)), eq("60"));
    }

    @Test
    void actionDimensionOverLimitIsRejectedWithoutTouchingActorDimension() {
        when(redisUtils.executeScript(any(), anyList(), any())).thenReturn(3L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.check(ACTOR_HASH, VideoInteractionAction.SHARE));

        assertEquals(429, error.getCode());
        // 第一维就超限：合计维度不消耗、也不查询。
        verify(redisUtils, times(1)).executeScript(any(), anyList(), any());
        verify(redisUtils, never()).executeScript(any(),
                eq(List.of("rate:video-interaction:actor:" + ACTOR_HASH)), any());
    }

    @Test
    void exactLimitIsAllowedAndOneMoreIsRejected() {
        when(redisUtils.executeScript(any(), anyList(), any()))
                .thenReturn(2L, 3L)
                .thenReturn(3L);
        assertDoesNotThrow(() -> service.check(ACTOR_HASH, VideoInteractionAction.PLAY));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.check(ACTOR_HASH, VideoInteractionAction.PLAY));
        assertEquals(429, error.getCode(),
                "单动作上限 2：第 3 次必须被拒绝（合计维度返回值 3 也在上限内）");
    }

    @Test
    void actorTotalDimensionIsEnforcedAcrossActions() {
        // 单动作维度都没超（1、1），但合计维度已到 4 > 3。
        when(redisUtils.executeScript(any(), anyList(), any()))
                .thenReturn(1L, 4L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.check(ACTOR_HASH, VideoInteractionAction.SHARE));

        assertEquals(429, error.getCode(),
                "每个动作各刷一份额度必须被“同一 actor 合计”维度拦住");
    }

    @Test
    void nullScriptResultIsFailClosed() {
        when(redisUtils.executeScript(any(), anyList(), any())).thenReturn(null);

        RedisOperationException error = assertThrows(RedisOperationException.class,
                () -> service.check(ACTOR_HASH, VideoInteractionAction.PLAY));

        assertEquals("交互准入限流返回非法值：null（本次计数已被拒绝）", error.getMessage());
    }

    @Test
    void negativeScriptResultIsFailClosed() {
        when(redisUtils.executeScript(any(), anyList(), any())).thenReturn(-1L);

        RedisOperationException error = assertThrows(RedisOperationException.class,
                () -> service.check(ACTOR_HASH, VideoInteractionAction.PLAY));

        assertEquals("交互准入限流返回非法值：-1（本次计数已被拒绝）", error.getMessage());
    }

    @Test
    void redisFailurePropagatesInsteadOfFailingOpen() {
        when(redisUtils.executeScript(any(), anyList(), any()))
                .thenThrow(new RedisOperationException("Redis 操作失败"));

        RedisOperationException error = assertThrows(RedisOperationException.class,
                () -> service.check(ACTOR_HASH, VideoInteractionAction.PLAY));

        assertEquals("Redis 操作失败", error.getMessage(), "异常不得被吞掉或降级为放行");
    }

    @Test
    void actionAndActorKeysAreDistinctSpecs() {
        when(redisUtils.executeScript(any(), anyList(), any())).thenReturn(1L);

        service.check(ACTOR_HASH, VideoInteractionAction.PLAY);
        service.check(ACTOR_HASH, VideoInteractionAction.SHARE);

        // 单动作维度按动作分键，合计维度是同一个键：PLAY/SHARE 各自一份额度，合计算在一起。
        verify(redisUtils).executeScript(any(),
                eq(List.of("rate:video-interaction:action:" + ACTOR_HASH + ":PLAY")), eq("60"));
        verify(redisUtils).executeScript(any(),
                eq(List.of("rate:video-interaction:action:" + ACTOR_HASH + ":SHARE")), eq("60"));
        verify(redisUtils, times(2)).executeScript(any(),
                eq(List.of("rate:video-interaction:actor:" + ACTOR_HASH)), eq("60"));
    }

    @Test
    void windowLengthComesFromConfiguration() {
        properties.setWindowSeconds(120);
        when(redisUtils.executeScript(any(), anyList(), any())).thenReturn(1L);

        service.check(ACTOR_HASH, VideoInteractionAction.PLAY);

        verify(redisUtils).executeScript(any(),
                eq(List.of("rate:video-interaction:action:" + ACTOR_HASH + ":PLAY")), eq("120"));
    }
}
