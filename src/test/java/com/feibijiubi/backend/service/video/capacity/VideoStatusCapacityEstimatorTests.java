package com.feibijiubi.backend.service.video.capacity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T14-A 容量估算纯计算用例：不连接 MySQL/Redis。
 *
 * <p>重点验收“数据不足一律 UNKNOWN、绝不回落为 0”，以及假设日事件量/保留天数只是配置假设。</p>
 */
class VideoStatusCapacityEstimatorTests {

    @Test
    void dbDailyBytesIsUnknownWhenAssumedEventVolumeMissing() {
        VideoStatusCapacityEstimator.Range range = VideoStatusCapacityEstimator.dbDailyBytes(
                inputs(0L, 200L, 100L, 80L, 120L, 10, 1_000_000L, 0L, 30));

        assertTrue(range.isUnknown());
        assertNull(range.low());
        assertNull(range.high());
        assertTrue(range.note().contains("假设日事件量"));
        assertFalse(range.format("B/天").contains("0 B/天"),
                "未提供假设时必须 UNKNOWN，不能显示 0 B/天");
    }

    @Test
    void dbDailyBytesUsesDocumentedEstimateBand() {
        VideoStatusCapacityEstimator.Range range = VideoStatusCapacityEstimator.dbDailyBytes(
                inputs(1_000L, 200L, 100L, 80L, 120L, 10, 1_000_000L, 0L, 30));

        assertEquals(VideoStatusCapacityEstimator.Range.ESTIMATED, range.status());
        assertEquals(150_000L, range.low(), "点估计 300000 × 0.5");
        assertEquals(450_000L, range.high(), "点估计 300000 × 1.5");
        assertTrue(range.note().contains("300000"), "点估计必须写进说明，便于复核");
        assertTrue(range.note().contains("非实测"));
    }

    @Test
    void dbDailyBytesIsUnknownWhenEitherRowSizeUnknown() {
        VideoStatusCapacityEstimator.Range range = VideoStatusCapacityEstimator.dbDailyBytes(
                inputs(1_000L, 200L, null, 80L, 120L, 10, 1_000_000L, 0L, 30));

        assertTrue(range.isUnknown());
        assertTrue(range.note().contains("video_status_consumed_event"),
                "必须指出是哪张表折算不出来，不能只说 unknown");
    }

    @Test
    void dbDailyBytesOverflowIsUnknownNotNegative() {
        VideoStatusCapacityEstimator.Range range = VideoStatusCapacityEstimator.dbDailyBytes(
                inputs(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 80L, 120L, 10,
                        1_000_000L, 0L, 30));

        assertTrue(range.isUnknown());
        assertNull(range.low());
        assertTrue(range.note().contains("溢出"));
    }

    @Test
    void processedDailyBytesRequiresMinimumSample() {
        VideoStatusCapacityEstimator.Range range = VideoStatusCapacityEstimator.processedDailyBytes(
                inputs(1_000L, 200L, 100L, 80L, 120L,
                        VideoStatusCapacityEstimator.MIN_PROCESSED_SAMPLE_KEYS - 1,
                        1_000_000L, 0L, 30));

        assertTrue(range.isUnknown());
        assertTrue(range.note().contains("样本不足"));
    }

    @Test
    void processedDailyBytesUsesMeasuredSampleMinMax() {
        VideoStatusCapacityEstimator.Range range = VideoStatusCapacityEstimator.processedDailyBytes(
                inputs(1_000L, 200L, 100L, 80L, 120L, 6, 1_000_000L, 0L, 30));

        assertEquals(80_000L, range.low(), "1000 事件/天 × 80 B/键");
        assertEquals(120_000L, range.high(), "1000 事件/天 × 120 B/键");
        assertTrue(range.note().contains("日事件量为配置假设"));
    }

    @Test
    void processedKeyCountAndRetainedBytesNeedRetentionDecision() {
        VideoStatusCapacityEstimator.Inputs inputs =
                inputs(1_000L, 200L, 100L, 80L, 120L, 10, 1_000_000L, 0L, 0);

        VideoStatusCapacityEstimator.Range count = VideoStatusCapacityEstimator.processedKeyCount(inputs);
        VideoStatusCapacityEstimator.Range retained =
                VideoStatusCapacityEstimator.processedRetainedBytes(inputs);

        assertTrue(count.isUnknown());
        assertTrue(count.note().contains("保留天数未决策"));
        assertTrue(retained.isUnknown());
        assertTrue(retained.note().contains("保留天数未决策"));
        assertFalse(retained.format("B").contains("0 B"), "保留期未定不能显示 0 占用");
    }

    @Test
    void processedKeyCountAndRetainedBytesUseAssumedRetention() {
        VideoStatusCapacityEstimator.Inputs inputs =
                inputs(1_000L, 200L, 100L, 80L, 120L, 10, 1_000_000L, 0L, 30);

        VideoStatusCapacityEstimator.Range count = VideoStatusCapacityEstimator.processedKeyCount(inputs);
        VideoStatusCapacityEstimator.Range retained =
                VideoStatusCapacityEstimator.processedRetainedBytes(inputs);

        assertEquals(30_000L, count.low());
        assertEquals(30_000L, count.high());
        assertTrue(count.note().contains("配置假设"));
        assertEquals(2_400_000L, retained.low(), "80000 B/天 × 30 天");
        assertEquals(3_600_000L, retained.high(), "120000 B/天 × 30 天");
    }

    @Test
    void exhaustionDaysIsUnknownWhenMaxmemoryUnlimited() {
        VideoStatusCapacityEstimator.Range range = VideoStatusCapacityEstimator.redisExhaustionDays(
                inputs(1_000L, 200L, 100L, 80L, 120L, 10, 1_000_000L, 0L, 30));

        assertTrue(range.isUnknown());
        assertTrue(range.note().contains("maxmemory=0"));
        assertFalse(range.format("天").contains("0 天"));
    }

    @Test
    void exhaustionDaysRangeUsesFreeMemoryAndDailyBounds() {
        // free = 10_000_000 - 2_000_000 = 8_000_000；日增 1_000_000～2_000_000 B
        VideoStatusCapacityEstimator.Range range = VideoStatusCapacityEstimator.redisExhaustionDays(
                inputs(1_000L, 200L, 100L, 1_000L, 2_000L, 10, 2_000_000L, 10_000_000L, 30));

        assertEquals(4L, range.low(), "最坏情况：8MB ÷ 2MB/天");
        assertEquals(8L, range.high(), "最好情况：8MB ÷ 1MB/天");
        assertTrue(range.note().contains("未含 current/dirty/hot"));
    }

    @Test
    void unknownRangeRejectsNumbersAndRendersReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new VideoStatusCapacityEstimator.Range(0L, null,
                        VideoStatusCapacityEstimator.Range.UNKNOWN, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new VideoStatusCapacityEstimator.Range(10L, 5L,
                        VideoStatusCapacityEstimator.Range.ESTIMATED, "x"));

        VideoStatusCapacityEstimator.Range unknown = VideoStatusCapacityEstimator.Range.unknown("没数据");
        assertEquals("UNKNOWN（没数据）", unknown.format("B/天"));
    }

    private static VideoStatusCapacityEstimator.Inputs inputs(
            long assumedDailyEvents,
            Long outboxBytesPerRow,
            Long consumedBytesPerRow,
            Long sampleMinBytes,
            Long sampleMaxBytes,
            int sampleCount,
            Long usedMemoryBytes,
            Long maxMemoryBytes,
            int assumedRetentionDays) {
        return new VideoStatusCapacityEstimator.Inputs(assumedDailyEvents, outboxBytesPerRow,
                consumedBytesPerRow, sampleMinBytes, sampleMaxBytes, sampleCount,
                usedMemoryBytes, maxMemoryBytes, assumedRetentionDays);
    }
}
