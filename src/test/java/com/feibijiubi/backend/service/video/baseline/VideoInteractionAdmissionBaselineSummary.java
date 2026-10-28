package com.feibijiubi.backend.service.video.baseline;

import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * T16-B0 同步准入基线测量用的<b>纯计算</b>分位数摘要（nearest-rank）。
 *
 * <p>只接收 {@code System.nanoTime()} 差值样本，不接收 vid / eventId / requestKey / visitor /
 * actorHash：本类生成的<b>数字部分</b>因此不含标识或凭据。但 {@link #describe(String)} 的标签是
 * 调用方自由字符串，标识仍可从标签进入报告文本——<b>标签安全只能由调用方保证</b>（见该方法 javadoc），
 * 本类无法强制也不做校验。不读环境、不打日志、不做 I/O：所有数字都由调用方传入，测试里可以直接核对边界。</p>
 *
 * <h2>为什么用 nearest-rank</h2>
 * <p>单 JVM、串行、几十到几百个样本的“观察值”不是延迟分布模型，插值分位数（线性插值、P² 近似）
 * 会给出一个<b>从未出现过</b>的耗时值，看起来更漂亮但不可核对。nearest-rank 直接返回第
 * {@code ceil(p/100 * n)} 个最小样本：每个输出都是真实测到的一次耗时，样本数少时也不会“平滑”掉
 * 长尾。</p>
 *
 * <h2>诚实边界</h2>
 * <ul>
 *   <li>这些数字是单机单线程的一小段样本，<b>不是</b>容量上限、吞吐、P99 SLA 或生产验收结论；</li>
 *   <li>耗时包含调用点附近的 JVM/GC/连接池噪声，样本数越小越不稳；</li>
 *   <li>{@link #describe(String)} 除调用方给的标签外，只输出样本数与三个分位数（毫秒），没有任何标识字段；
 *       标识能不能出现在输出里，完全取决于标签内容（本类不校验）。</li>
 * </ul>
 */
public final class VideoInteractionAdmissionBaselineSummary {

    /** 无样本时的后缀：空摘要返回空 Optional，绝不用 0 代替“没测到”。 */
    private static final String EMPTY_SUFFIX = "（无样本）";

    /** 升序样本（纳秒）。构造后不再修改。 */
    private final long[] sortedNanos;

    private VideoInteractionAdmissionBaselineSummary(long[] sortedNanos) {
        this.sortedNanos = sortedNanos;
    }

    /**
     * 用原始样本构造摘要。
     *
     * <p>样本会先 clone 再排序：调用方传进来的数组<b>保持原样</b>，所以“传入顺序”与“样本下标”
     * 不会因为这里排序而错位（单测钉住了这一点）。</p>
     *
     * @param samplesNanos {@code System.nanoTime()} 差值，允许乱序；不得为 {@code null} 或含负数
     * @throws IllegalArgumentException 数组为 {@code null} 或含负数（负耗时只可能是测量代码写错）
     */
    public static VideoInteractionAdmissionBaselineSummary of(long[] samplesNanos) {
        if (samplesNanos == null) {
            throw new IllegalArgumentException("耗时样本数组不能为 null");
        }
        long[] copy = samplesNanos.clone();
        for (long sample : copy) {
            if (sample < 0L) {
                throw new IllegalArgumentException("耗时样本不能为负数，当前值：" + sample);
            }
        }
        Arrays.sort(copy);
        return new VideoInteractionAdmissionBaselineSummary(copy);
    }

    /** 样本数（不含被刻意排除的 warmup 请求）。 */
    public int sampleCount() {
        return sortedNanos.length;
    }

    /**
     * nearest-rank 分位数。
     *
     * @param percentile 1..100
     * @return 第 {@code ceil(percentile/100 * n)} 个最小样本（纳秒）；无样本时为空
     * @throws IllegalArgumentException 百分位不在 1..100
     */
    public OptionalLong percentileNanos(int percentile) {
        if (percentile < 1 || percentile > 100) {
            throw new IllegalArgumentException("百分位必须在 1..100 之间，当前值：" + percentile);
        }
        if (sortedNanos.length == 0) {
            return OptionalLong.empty();
        }
        // 整数算 rank，避免 0.95 * 20 这类浮点误差把 rank 推成 20 或 18。
        long rank = ((long) percentile * sortedNanos.length + 99L) / 100L;
        int index = (int) Math.max(1L, Math.min(rank, sortedNanos.length)) - 1;
        return OptionalLong.of(sortedNanos[index]);
    }

    public OptionalLong p50Nanos() {
        return percentileNanos(50);
    }

    public OptionalLong p95Nanos() {
        return percentileNanos(95);
    }

    public OptionalLong p99Nanos() {
        return percentileNanos(99);
    }

    /**
     * 一行可打印摘要：{@code 标签 n=.. p50=..ms p95=..ms p99=..ms}；无样本时
     * {@code 标签 n=0（无样本）}。
     *
     * <p>标签由调用方给出，只应是“阶段/指标名”（例如 {@code single-video admission}），
     * 不得放入 vid、eventId、requestKey、visitor 或连接信息；<b>本类不校验标签内容</b>，
     * 所以标识安全是调用方的责任，不是本类提供的保证。毫秒固定三位小数并使用
     * {@link Locale#ROOT}，避免不同机器的小数点符号影响日志比对。</p>
     */
    public String describe(String label) {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("摘要标签不能为空");
        }
        StringBuilder line = new StringBuilder(label.trim())
                .append(" n=").append(sortedNanos.length);
        if (sortedNanos.length == 0) {
            return line.append(EMPTY_SUFFIX).toString();
        }
        return line
                .append(" p50=").append(millis(p50Nanos()))
                .append(" p95=").append(millis(p95Nanos()))
                .append(" p99=").append(millis(p99Nanos()))
                .toString();
    }

    private static String millis(OptionalLong nanos) {
        return String.format(Locale.ROOT, "%.3fms", nanos.orElseThrow() / 1_000_000.0d);
    }
}
