package com.feibijiubi.backend.common;

import com.feibijiubi.backend.video.converter.VideoConverter;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.event.VideoStatusDelta;
import com.feibijiubi.backend.video.vo.AdminVideoDetailVO;
import com.feibijiubi.backend.video.vo.VideoDetailVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T02 纯单元回归：计数统一 Long、范围校验、累计溢出与 VO 透传。
 *
 * <p>不连接数据库/Redis/MQ；DB 与 Redis 的真实路径由
 * {@code VideoStatusCounterIsolatedTests}（-DvideoStatusIT=true）覆盖。</p>
 */
class VideoStatusCounterRangeTests {

    @Test
    void parsesThreeBillionExactly() {
        assertEquals(3_000_000_000L,
                VideoStatusCounterRange.parse("3000000000", "playTimes"));
    }

    @Test
    void parsesLongMaxExactly() {
        assertEquals(Long.MAX_VALUE,
                VideoStatusCounterRange.parse("9223372036854775807", "playTimes"));
    }

    @Test
    void rejectsAboveLongMaxInsteadOfWrapping() {
        VideoStatusCounterRangeException error = assertThrows(
                VideoStatusCounterRangeException.class,
                () -> VideoStatusCounterRange.parse("9223372036854775808", "playTimes")
        );
        assertEquals(VideoStatusCounterRangeException.Reason.OVERFLOW,
                error.getReason());
    }

    @Test
    void rejectsBadRedisFieldsInsteadOfSilentZero() {
        assertReason("", VideoStatusCounterRangeException.Reason.MISSING);
        assertReason(null, VideoStatusCounterRangeException.Reason.MISSING);
        assertReason("abc", VideoStatusCounterRangeException.Reason.MALFORMED);
        assertReason("1.5", VideoStatusCounterRangeException.Reason.MALFORMED);
        assertReason("1e3", VideoStatusCounterRangeException.Reason.MALFORMED);
        assertReason("-1", VideoStatusCounterRangeException.Reason.NEGATIVE);
    }

    private static void assertReason(
            String raw,
            VideoStatusCounterRangeException.Reason reason
    ) {
        VideoStatusCounterRangeException error = assertThrows(
                VideoStatusCounterRangeException.class,
                () -> VideoStatusCounterRange.parse(raw, "field")
        );
        assertEquals(reason, error.getReason());
    }

    @Test
    void addExactAppliesSignedDeltaToNonNegativeCurrent() {
        // 有符号增量：允许用负增量抵销已有正向计数。
        assertEquals(0L,
                VideoStatusCounterRange.addExact(1L, -1L, "likeTimes"));
        assertEquals(5L,
                VideoStatusCounterRange.addExact(10L, -5L, "collectTimes"));
        assertEquals(Long.MAX_VALUE,
                VideoStatusCounterRange.addExact(Long.MAX_VALUE - 1, 1, "playTimes"));
    }

    @Test
    void addExactRejectsNegativeResultInsteadOfClampingToZero() {
        // 0 + (-1) 是非法输入：必须拒绝，不能 clamp 成 0（否则非法取消被静默改成合法状态）。
        VideoStatusCounterRangeException zeroMinusOne = assertThrows(
                VideoStatusCounterRangeException.class,
                () -> VideoStatusCounterRange.addExact(0L, -1L, "likeTimes")
        );
        assertEquals(VideoStatusCounterRangeException.Reason.NEGATIVE,
                zeroMinusOne.getReason());
        assertEquals("likeTimes", zeroMinusOne.getField());

        // 抵销幅度大于当前值、以及当前值本身为负，同样归类为 NEGATIVE。
        assertAddExactReason(VideoStatusCounterRangeException.Reason.NEGATIVE,
                1L, -2L);
        assertAddExactReason(VideoStatusCounterRangeException.Reason.NEGATIVE,
                -1L, 1L);
        assertAddExactReason(VideoStatusCounterRangeException.Reason.NEGATIVE,
                0L, Long.MIN_VALUE);
    }

    @Test
    void addExactRejectsOverflowInsteadOfWrapping() {
        // 非负当前值加正向增量只可能向上溢出。
        assertAddExactReason(VideoStatusCounterRangeException.Reason.OVERFLOW,
                Long.MAX_VALUE, 1L);
        assertAddExactReason(VideoStatusCounterRangeException.Reason.OVERFLOW,
                Long.MAX_VALUE - 2, 3L);
    }

    private static void assertAddExactReason(
            VideoStatusCounterRangeException.Reason reason,
            long current,
            long delta
    ) {
        VideoStatusCounterRangeException error = assertThrows(
                VideoStatusCounterRangeException.class,
                () -> VideoStatusCounterRange.addExact(current, delta, "field")
        );
        assertEquals(reason, error.getReason());
    }

    @Test
    void deltaBatchAccumulationStaysSignedAndDoesNotWrap() {
        // VideoStatusDelta.plus 是有符号累计：允许中间/最终为负，不在这一层判定非负。
        VideoStatusDelta net = new VideoStatusDelta(100, 100, 0, 0, 0, 0, 0, 0)
                .plus(new VideoStatusDelta(-40, -100, 10, 0, 0, 0, 0, 0));
        assertEquals(60L, net.playDelta());
        assertEquals(0L, net.likeDelta());
        assertEquals(10L, net.unlikeDelta());

        VideoStatusDelta negative = VideoStatusDelta.zero()
                .plus(new VideoStatusDelta(-5, 0, 0, 0, 0, 0, 0, 0));
        assertEquals(-5L, negative.playDelta());

        // 溢出仍然必须显式失败，不能静默回绕。
        VideoStatusDelta one = new VideoStatusDelta(
                Long.MAX_VALUE, 0, 0, 0, 0, 0, 0, 0);
        assertThrows(ArithmeticException.class,
                () -> one.plus(new VideoStatusDelta(1, 0, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    void threeBillionSurvivesEntityToVoConversion() {
        VideoStatus status = new VideoStatus(
                1, 3_000_000_000L, 3_000_000_000L, 0L, 0L, 0L, 0L, 0L, 0L);
        Video video = new Video();
        video.setVid(1);
        video.setUid(2);

        VideoDetailVO detail = VideoConverter.toVideoDetailVO(
                video, status, null, null, null, null, null);
        AdminVideoDetailVO admin = VideoConverter.toAdminVideoDetailVO(
                video, status, null, null, null);

        assertEquals(3_000_000_000L, detail.getPlayTimes());
        assertEquals(3_000_000_000L, detail.getLikeTimes());
        assertEquals(3_000_000_000L, admin.getPlayTimes());
        assertEquals(Long.MAX_VALUE, VideoStatusCounterRange.parse(
                String.valueOf(Long.MAX_VALUE), "playTimes"));
    }
}
