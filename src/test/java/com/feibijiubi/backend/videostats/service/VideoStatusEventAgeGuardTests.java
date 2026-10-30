package com.feibijiubi.backend.videostats.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T14-S1 普通重放绝对年龄判定：固定基准时间，验证窗口内 / 恰好 30 天 / 超出 / 纳秒 /
 * 未来时间 / 默认时区。纯函数，不连任何基础设施。
 */
class VideoStatusEventAgeGuardTests {

    private static final Instant FIXED_INSTANT =
            Instant.parse("2026-10-24T03:04:05.123456789Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(FIXED_INSTANT, ZoneId.systemDefault());
    private static final LocalDateTime NOW = LocalDateTime.now(FIXED_CLOCK);

    @Test
    void maxNormalReplayAgeIsFixedAtThirtyDays() {
        assertEquals(30, VideoStatusEventAgeGuard.MAX_NORMAL_REPLAY_AGE_DAYS);
        assertTrue(VideoStatusEventAgeGuard.EXPIRED_REASON.contains("30"));
    }

    @Test
    void eventInsideWindowIsAllowed() {
        assertFalse(VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(
                NOW.minusDays(29),
                NOW
        ));
    }

    @Test
    void eventExactlyThirtyDaysOldIsAllowed() {
        // 边界是“严格早于”：恰好 30 天仍属普通重放窗口。
        assertFalse(VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(
                NOW.minusDays(30),
                NOW
        ));
    }

    @Test
    void eventOneNanoBeyondThirtyDaysIsRejected() {
        assertTrue(VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(
                NOW.minusDays(30).minusNanos(1),
                NOW
        ));
    }

    @Test
    void nanosAreNotTruncatedAtTheBoundary() {
        assertFalse(VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(
                NOW.minusDays(30).plusNanos(1),
                NOW
        ));
        assertTrue(VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(
                NOW.minusDays(30).minusNanos(1),
                NOW
        ));
    }

    @Test
    void eventOneSecondBeyondThirtyDaysIsRejected() {
        assertTrue(VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(
                NOW.minusDays(30).minusSeconds(1),
                NOW
        ));
    }

    @Test
    void futureEventIsNotRejected() {
        // 未来时间不新增拒绝策略，保持既有行为；这不是防篡改机制。
        assertFalse(VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(
                NOW.plusDays(1),
                NOW
        ));
    }

    @Test
    void defaultZoneClockKeepsZonelessWallClockSemantics() {
        // 生产时钟是 Clock.systemDefaultZone()：LocalDateTime.now(clock) 必须与既有的
        // LocalDateTime.now()（无时区语义）落在同一段墙钟时间内，否则 30 天会算偏。
        LocalDateTime before = LocalDateTime.now();
        LocalDateTime fromDefaultZoneClock =
                LocalDateTime.now(Clock.systemDefaultZone());
        LocalDateTime after = LocalDateTime.now();

        assertFalse(fromDefaultZoneClock.isBefore(before));
        assertFalse(fromDefaultZoneClock.isAfter(after));
    }

    @Test
    void fixedClockVerdictMatchesExplicitNow() {
        LocalDateTime fromClock = LocalDateTime.now(FIXED_CLOCK);
        assertEquals(
                VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(
                        NOW.minusDays(31),
                        NOW
                ),
                VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(
                        fromClock.minusDays(31),
                        fromClock
                )
        );
        assertTrue(VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(
                fromClock.minusDays(31),
                fromClock
        ));
    }
}
