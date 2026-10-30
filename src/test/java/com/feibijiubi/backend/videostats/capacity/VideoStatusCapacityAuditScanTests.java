package com.feibijiubi.backend.videostats.capacity;

import com.feibijiubi.backend.infrastructure.redis.RedisConstants;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T14-A 有界 SCAN 的确定性用例（纯内存，无基础设施）：证明真正的硬上限是 <b>SCAN 命令次数</b>，
 * 不是“访问 key 数”，也不是 {@code COUNT}。
 *
 * <p>父审问题：旧实现用 Spring Data 的 {@code Cursor#hasNext()} + {@code MATCH}，
 * {@code hasNext()} 在匹配为空时会自己连续请求 SCAN 直到游标归零，所以
 * {@code scanMaxIterations} 实际上被绕过；{@code COUNT} 只是 hint，访问 key 数也不能当命令计数。
 * 这里用脚本化单页读取器逐条锁住新行为：</p>
 * <ul>
 *   <li>多页均空且游标非 0：严格在命令预算处停下，<b>不</b>继续追到遍历结束；</li>
 *   <li>零匹配但游标归零：算遍历完成（命令数 = 实际往返数）；</li>
 *   <li>少量匹配：跨页累计并按 min/max/avg 采样；</li>
 *   <li>单页大于 {@code COUNT}：COUNT 不是硬返回上限；采样数量另有独立硬上限；</li>
 *   <li>采样上限到达但游标非 0：截断而不是完成；</li>
 *   <li>SCAN 读取失败 / 空游标 / MEMORY USAGE 无结果：UNKNOWN，不谎报完成。</li>
 * </ul>
 */
class VideoStatusCapacityAuditScanTests {

    private static final String PATTERN = RedisConstants.PROCESSED_PREFIX + "t14a-unit-*";

    // ------------------------------------------------------------- 命令次数才是硬上限

    @Test
    void emptyMatchPagesWithNonZeroCursorStopExactlyAtCommandLimit() {
        ScriptedScan scan = new ScriptedScan(page("17"), page("33"), page("51"));
        CountingSizes sizes = new CountingSizes(128L);

        VideoStatusCapacityReport.ProcessedKeySample sample =
                VideoStatusCapacityAudit.sampleWith(scan, sizes, options(5, 10, 50));

        assertEquals(5, scan.calls(), "空匹配页 + 游标非 0 时，命令次数必须正好等于预算（不多发一次）");
        assertEquals(5, sample.budget().scanCommands());
        assertEquals(5, sample.budget().scanCommandLimit());
        assertEquals(0, sample.returnedKeys(), "空匹配页不返回 key");
        assertEquals(0, sizes.calls(), "没有 key 就不该发 MEMORY USAGE");
        assertFalse(sample.scanComplete(), "空匹配页永远不能算遍历完成");
        assertEquals(VideoStatusCapacityReport.SAMPLE_STATUS_UNKNOWN, sample.status());
        assertTrue(sample.note().contains("样本不足"), sample.note());
        assertTrue(sample.note().contains("SCAN 命令 5/5 次"), sample.note());
        assertTrue(sample.note().contains("游标归零=否（覆盖截断）"), sample.note());
    }

    @Test
    void emptyMatchPagesOverManyAvailablePagesStillStopAtBudget() {
        // 读取器永远返回“空页 + 非 0 游标”，模拟 keyspace 远大于本次预算。
        ScriptedScan scan = new ScriptedScan(page("9"));
        VideoStatusCapacityReport.ProcessedKeySample sample =
                VideoStatusCapacityAudit.sampleWith(scan, key -> 128L, options(3, 1, 50));

        assertEquals(3, scan.calls());
        assertEquals(3, sample.budget().scanCommands());
        assertFalse(sample.scanComplete());
        assertEquals(0, sample.returnedKeys());
    }

    @Test
    void zeroMatchesWithFinishedCursorIsCompleteAfterTheRealRoundTrips() {
        ScriptedScan scan = new ScriptedScan(page("23"), page("0"));

        VideoStatusCapacityReport.ProcessedKeySample sample =
                VideoStatusCapacityAudit.sampleWith(scan, key -> 128L, options(10, 10, 50));

        assertEquals(2, scan.calls(), "游标归零是唯一的完成信号：两页就两次命令");
        assertEquals(2, sample.budget().scanCommands());
        assertTrue(sample.scanComplete());
        assertEquals(0, sample.returnedKeys());
        assertEquals(VideoStatusCapacityReport.SAMPLE_STATUS_UNKNOWN, sample.status());
        assertTrue(sample.note().contains("游标归零=是"), sample.note());
    }

    // ------------------------------------------------------------------ 采样与边界

    @Test
    void smallMatchSetIsSampledAcrossPagesInScanOrder() {
        ScriptedScan scan = new ScriptedScan(
                page("9", "a1", "a2"),
                page("0", "a3", "a4", "a5"));
        Map<String, Long> sizes = new LinkedHashMap<>();
        sizes.put("a1", 100L);
        sizes.put("a2", 120L);
        sizes.put("a3", 140L);
        sizes.put("a4", 160L);
        sizes.put("a5", 180L);

        VideoStatusCapacityReport.ProcessedKeySample sample =
                VideoStatusCapacityAudit.sampleWith(scan, sizeByKey(sizes), options(10, 10, 50));

        assertEquals(2, scan.calls());
        assertEquals(2, sample.budget().scanCommands());
        assertEquals(5, sample.returnedKeys());
        assertEquals(5, sample.sampledKeys());
        assertTrue(sample.scanComplete());
        assertEquals(VideoStatusCapacityReport.SAMPLE_STATUS_SAMPLED, sample.status());
        assertEquals(100L, sample.minBytes());
        assertEquals(180L, sample.maxBytes());
        assertEquals(140L, sample.averageBytes());
        assertNull(sample.note(), "完整遍历且有足够样本时不应有覆盖告警");
    }

    @Test
    void pageLargerThanCountHintIsFullyCountedAndSamplingKeepsItsOwnLimit() {
        String[] keys = new String[25];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = "big-" + i;
        }
        ScriptedScan scan = new ScriptedScan(page("0", keys));
        CountingSizes sizes = new CountingSizes(256L);

        // COUNT hint = 5，但单页返回 25 个 key：COUNT 不是硬返回上限；采样上限 10 独立生效。
        VideoStatusCapacityReport.ProcessedKeySample sample =
                VideoStatusCapacityAudit.sampleWith(scan, sizes, options(10, 5, 10));

        assertEquals(1, scan.calls());
        assertEquals(1, sample.budget().scanCommands());
        assertEquals(25, sample.returnedKeys(), "SCAN 返回的 key 数如实记录，不受 COUNT 限制");
        assertEquals(10, sample.sampledKeys(), "MEMORY USAGE 采样数量是独立硬上限");
        assertEquals(10, sizes.calls());
        assertTrue(sample.scanComplete(), "本页游标已是 0，遍历确实是完成的");
        assertEquals(10, sample.budget().sampleKeyLimit());
        assertEquals(5, sample.budget().countHint());
    }

    @Test
    void sampleLimitReachedBeforeCursorFinishesIsTruncatedNotComplete() {
        ScriptedScan scan = new ScriptedScan(page("41", "b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8"));
        CountingSizes sizes = new CountingSizes(64L);

        VideoStatusCapacityReport.ProcessedKeySample sample =
                VideoStatusCapacityAudit.sampleWith(scan, sizes, options(10, 10, 5));

        assertEquals(1, scan.calls(), "采样上限到达后立即停，不再继续追游标");
        assertEquals(5, sample.sampledKeys());
        assertEquals(8, sample.returnedKeys());
        assertEquals(5, sizes.calls());
        assertFalse(sample.scanComplete(), "游标非 0 就是覆盖截断");
        assertTrue(sample.note().contains("游标归零=否（覆盖截断）"), sample.note());
        assertTrue(sample.note().contains("样本只覆盖已扫描部分"), sample.note());
        assertEquals(VideoStatusCapacityReport.SAMPLE_STATUS_SAMPLED, sample.status(),
                "已采样的字节仍是真实测量值，但覆盖范围被标注为截断");
    }

    // --------------------------------------------------------------------- 失败语义

    @Test
    void scanReadFailureStopsImmediatelyAndBecomesUnknown() {
        AtomicInteger calls = new AtomicInteger();
        VideoStatusCapacityAudit.ScanPageReader scan = cursor -> {
            if (calls.incrementAndGet() == 1) {
                return page("77", "c1");
            }
            throw new IllegalStateException("connection reset");
        };
        CountingSizes sizes = new CountingSizes(64L);

        VideoStatusCapacityReport.ProcessedKeySample sample =
                VideoStatusCapacityAudit.sampleWith(scan, sizes, options(5, 10, 50));

        assertEquals(2, calls.get(), "失败后立刻停止，不再重试或继续追游标");
        assertEquals(2, sample.budget().scanCommands());
        assertEquals(1, sample.returnedKeys());
        assertFalse(sample.scanComplete());
        assertEquals(VideoStatusCapacityReport.SAMPLE_STATUS_UNKNOWN, sample.status());
        assertNull(sample.minBytes(), "读失败不得给出字节数");
        assertTrue(sample.note().contains("SCAN 读取失败（IllegalStateException）"), sample.note());
        assertTrue(sample.note().contains("游标归零=否"), sample.note());
    }

    @Test
    void blankOrMissingCursorIsAFailureInsteadOfCompletion() {
        ScriptedScan blank = new ScriptedScan(page("", "d1"));
        VideoStatusCapacityReport.ProcessedKeySample blankSample =
                VideoStatusCapacityAudit.sampleWith(blank, key -> 64L, options(5, 10, 50));

        assertEquals(1, blank.calls(), "游标不可判定时立刻停，绝不继续循环");
        assertFalse(blankSample.scanComplete());
        assertEquals(VideoStatusCapacityReport.SAMPLE_STATUS_UNKNOWN, blankSample.status());
        assertTrue(blankSample.note().contains("SCAN 返回空游标"), blankSample.note());

        AtomicInteger nullCalls = new AtomicInteger();
        VideoStatusCapacityAudit.ScanPageReader nullPage = cursor -> {
            nullCalls.incrementAndGet();
            return null;
        };
        VideoStatusCapacityReport.ProcessedKeySample nullSample =
                VideoStatusCapacityAudit.sampleWith(nullPage, key -> 64L, options(5, 10, 50));

        assertEquals(1, nullCalls.get());
        assertFalse(nullSample.scanComplete());
        assertEquals(VideoStatusCapacityReport.SAMPLE_STATUS_UNKNOWN, nullSample.status());
    }

    @Test
    void memoryUsageWithoutResultIsCountedAsMissAndNeverFabricated() {
        ScriptedScan scan = new ScriptedScan(page("0", "e1", "e2", "e3", "e4", "e5", "e6"));
        VideoStatusCapacityAudit.KeySizeReader sizes = key -> {
            throw new IllegalStateException("MEMORY USAGE failed");
        };

        VideoStatusCapacityReport.ProcessedKeySample sample =
                VideoStatusCapacityAudit.sampleWith(scan, sizes, options(5, 10, 50));

        assertEquals(1, scan.calls());
        assertEquals(0, sample.sampledKeys());
        assertEquals(6, sample.returnedKeys());
        assertEquals(VideoStatusCapacityReport.SAMPLE_STATUS_UNKNOWN, sample.status());
        assertNull(sample.minBytes());
        assertTrue(sample.note().contains("MEMORY USAGE 无结果 6 个"), sample.note());
    }

    @Test
    void optionsRejectNonPositiveScanCommandLimit() {
        assertThrows(IllegalArgumentException.class, () -> options(0, 10, 50),
                "命令预算为 0 就等于没有抽样，必须拒绝");
        assertThrows(IllegalArgumentException.class, () -> options(5, 0, 50),
                "COUNT 必须为正");
        assertThrows(IllegalArgumentException.class, () -> options(5, 10, 0),
                "采样上限必须为正");
    }

    // ------------------------------------------------------------------- fixtures

    private static VideoStatusCapacityAudit.Options options(int scanCommands, int countHint, int sampleKeys) {
        return new VideoStatusCapacityAudit.Options(
                20, 30, 1_000L, 30, scanCommands, countHint, sampleKeys, PATTERN);
    }

    private static VideoStatusCapacityAudit.ScanPage page(String cursor, String... keys) {
        List<byte[]> encoded = new ArrayList<>(keys.length);
        for (String key : keys) {
            encoded.add(key.getBytes(StandardCharsets.UTF_8));
        }
        return new VideoStatusCapacityAudit.ScanPage(encoded, cursor);
    }

    private static VideoStatusCapacityAudit.KeySizeReader sizeByKey(Map<String, Long> sizes) {
        return key -> {
            Long size = sizes.get(new String(key, StandardCharsets.UTF_8));
            assertNotNull(size, "unexpected key in sample: " + new String(key, StandardCharsets.UTF_8));
            return size;
        };
    }

    /** 依次返回脚本页；脚本用完后一直返回最后一页，模拟“游标永远不回到 0”。 */
    private static final class ScriptedScan implements VideoStatusCapacityAudit.ScanPageReader {

        private final List<VideoStatusCapacityAudit.ScanPage> pages;
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedScan(VideoStatusCapacityAudit.ScanPage... pages) {
            this.pages = List.of(pages);
        }

        @Override
        public VideoStatusCapacityAudit.ScanPage scan(String cursor) {
            int call = calls.getAndIncrement();
            return pages.get(Math.min(call, pages.size() - 1));
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class CountingSizes implements VideoStatusCapacityAudit.KeySizeReader {

        private final long size;
        private final AtomicInteger calls = new AtomicInteger();

        CountingSizes(long size) {
            this.size = size;
        }

        @Override
        public Long sizeOf(byte[] key) {
            calls.incrementAndGet();
            return size;
        }

        int calls() {
            return calls.get();
        }
    }
}
