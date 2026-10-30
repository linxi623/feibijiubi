package com.feibijiubi.backend.interaction.baseline;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VideoInteractionAdmissionBaselineSummary} 的纯单测：不连 DB/Redis/MQ，不起容器。
 *
 * <p>只钉住三件事：</p>
 * <ol>
 *   <li><b>空样本</b>不会退化成 “0 ms”：样本数 0，分位数返回空 Optional，文本明确写 “无样本”；</li>
 *   <li><b>nearest-rank</b> 取的是真实存在的第 {@code ceil(p/100 * n)} 个最小样本，
 *       不是插值出来的值 —— 1..200 的样本 p50=100、p95=190、p99=198；</li>
 *   <li>分位数与输入顺序无关，非法输入（null / 负数 / 百分位越界 / 空标签）直接报错，
 *       不会静默返回一个看起来合理的数字。</li>
 * </ol>
 */
class VideoInteractionAdmissionBaselineSummaryTests {

    @Test
    void emptySamplesReportZeroCountAndNoPercentileInsteadOfZeroMillis() {
        VideoInteractionAdmissionBaselineSummary summary =
                VideoInteractionAdmissionBaselineSummary.of(new long[0]);

        assertEquals(0, summary.sampleCount());
        assertEquals(OptionalLong.empty(), summary.p50Nanos());
        assertEquals(OptionalLong.empty(), summary.p95Nanos());
        assertEquals(OptionalLong.empty(), summary.p99Nanos());

        String text = summary.describe("single-video admission");
        assertEquals("single-video admission n=0（无样本）", text);
        assertFalse(text.contains("ms"), "空样本不得输出 0ms 之类的假数字: " + text);
    }

    @Test
    void singleSampleIsReportedAsAllThreePercentiles() {
        long onlySample = 1_500_000L;

        VideoInteractionAdmissionBaselineSummary summary =
                VideoInteractionAdmissionBaselineSummary.of(new long[]{onlySample});

        assertEquals(1, summary.sampleCount());
        assertEquals(OptionalLong.of(onlySample), summary.p50Nanos());
        assertEquals(OptionalLong.of(onlySample), summary.p95Nanos());
        assertEquals(OptionalLong.of(onlySample), summary.p99Nanos());
        assertEquals("warm admission n=1 p50=1.500ms p95=1.500ms p99=1.500ms",
                summary.describe("warm admission"));
    }

    @Test
    void twoHundredSamplesUseNearestRankWithoutInterpolation() {
        long[] samples = new long[200];
        for (int i = 0; i < samples.length; i++) {
            // 故意用“纳秒 = 序号”，断言里就能直接看出取的是第几个样本。
            samples[i] = i + 1L;
        }

        VideoInteractionAdmissionBaselineSummary summary =
                VideoInteractionAdmissionBaselineSummary.of(samples);

        assertEquals(200, summary.sampleCount());
        // ceil(0.50 * 200) = 100，ceil(0.95 * 200) = 190，ceil(0.99 * 200) = 198。
        assertEquals(OptionalLong.of(100L), summary.p50Nanos());
        assertEquals(OptionalLong.of(190L), summary.p95Nanos());
        assertEquals(OptionalLong.of(198L), summary.p99Nanos());
        // 插值分位数会给出 100.5 / 190.05 这种“没测到过”的值，nearest-rank 不会。
        assertEquals(OptionalLong.of(200L), summary.percentileNanos(100));
    }

    @Test
    void nearestRankBoundariesFollowCeilingRuleOnTwentySamples() {
        long[] samples = new long[20];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = i + 1L;
        }
        VideoInteractionAdmissionBaselineSummary summary =
                VideoInteractionAdmissionBaselineSummary.of(samples);

        // ceil(0.05*20)=1, ceil(0.50*20)=10, ceil(0.95*20)=19, ceil(0.99*20)=20
        assertEquals(OptionalLong.of(1L), summary.percentileNanos(1));
        assertEquals(OptionalLong.of(1L), summary.percentileNanos(5));
        assertEquals(OptionalLong.of(10L), summary.p50Nanos());
        assertEquals(OptionalLong.of(19L), summary.p95Nanos());
        assertEquals(OptionalLong.of(20L), summary.p99Nanos());
    }

    @Test
    void percentilesAreOrderIndependentBecauseInputIsSorted() {
        long[] descending = new long[]{500L, 400L, 300L, 200L, 100L};
        long[] shuffled = new long[]{300L, 100L, 500L, 200L, 400L};

        VideoInteractionAdmissionBaselineSummary a =
                VideoInteractionAdmissionBaselineSummary.of(descending);
        VideoInteractionAdmissionBaselineSummary b =
                VideoInteractionAdmissionBaselineSummary.of(shuffled);

        assertEquals(b.p50Nanos(), a.p50Nanos());
        assertEquals(b.p95Nanos(), a.p95Nanos());
        assertEquals(b.p99Nanos(), a.p99Nanos());
        // ceil(0.5*5)=3 -> 第 3 小 = 300
        assertEquals(OptionalLong.of(300L), a.p50Nanos());
    }

    @Test
    void duplicateSamplesKeepTheirCount() {
        long[] samples = new long[]{7L, 7L, 7L, 9L};

        VideoInteractionAdmissionBaselineSummary summary =
                VideoInteractionAdmissionBaselineSummary.of(samples);

        assertEquals(4, summary.sampleCount());
        assertEquals(OptionalLong.of(7L), summary.p50Nanos());
        assertEquals(OptionalLong.of(9L), summary.p99Nanos());
    }

    @Test
    void ofDoesNotReorderOrMutateTheCallersArray() {
        // 排序必须发生在内部副本上：若改成就地 Arrays.sort(samplesNanos)，调用方传入的
        // 计时列表就会被偷偷重排（后续按下标对上其他数据时会错位）。
        long[] input = new long[]{3L, 1L, 2L};

        VideoInteractionAdmissionBaselineSummary summary =
                VideoInteractionAdmissionBaselineSummary.of(input);

        assertArrayEquals(new long[]{3L, 1L, 2L}, input, "of() 不得修改/重排调用方传入的数组");
        // 内部仍然是有序的：ceil(0.5*3)=2 -> 第 2 小 = 2
        assertEquals(OptionalLong.of(2L), summary.p50Nanos());
        assertEquals(OptionalLong.of(3L), summary.p99Nanos());
    }

    @Test
    void invalidInputsFailInsteadOfProducingAPlausibleNumber() {
        assertThrows(IllegalArgumentException.class,
                () -> VideoInteractionAdmissionBaselineSummary.of(null));
        assertThrows(IllegalArgumentException.class,
                () -> VideoInteractionAdmissionBaselineSummary.of(new long[]{-1L}));

        VideoInteractionAdmissionBaselineSummary summary =
                VideoInteractionAdmissionBaselineSummary.of(new long[]{1L});
        assertThrows(IllegalArgumentException.class, () -> summary.percentileNanos(0));
        assertThrows(IllegalArgumentException.class, () -> summary.percentileNanos(101));
        assertThrows(IllegalArgumentException.class, () -> summary.describe(null));
        assertThrows(IllegalArgumentException.class, () -> summary.describe("  "));
    }

    @Test
    void describeUsesDotmillisAndAClippedLabel() {
        String text = VideoInteractionAdmissionBaselineSummary
                .of(new long[]{2_000_000L, 4_000_000L})
                .describe("  ten-videos admission  ");

        assertTrue(text.startsWith("ten-videos admission n=2 "), text);
        // ceil(0.50*2)=1 -> 2.000ms；ceil(0.95*2)=ceil(0.99*2)=2 -> 4.000ms
        assertTrue(text.endsWith(" p50=2.000ms p95=4.000ms p99=4.000ms"), text);
    }
}
