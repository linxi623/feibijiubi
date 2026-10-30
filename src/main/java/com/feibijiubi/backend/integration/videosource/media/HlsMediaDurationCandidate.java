package com.feibijiubi.backend.integration.videosource.media;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * M1：一份 HLS 媒体清单解析出的<b>候选</b>时长。
 *
 * <p>它只有三个安全字段：分片 {@code #EXTINF} 十进制精确求和的秒数、完整分片数、原始字节的 SHA-256。
 * 它<b>不是</b>"已确认时长"、<b>不是</b>"已发布"、<b>不是</b>"可播放"：清单自报的时长可能错误，
 * {@code #EXT-X-ENDLIST} 也不证明分片地址永久有效，因此这里的数值只能作为管理员确认前的候选值，
 * 必须经人工确认或修正后才能进入业务数据。模型里刻意没有 {@code confirmed}/{@code playable} 之类的字段，
 * 调用方无法从本记录推断出"已经可以播"。</p>
 *
 * <p>{@code manifestSha256} 只是"哪一份字节被解析过"的版本证据，<b>不是</b>签名、<b>不是</b>可信认证：
 * 上游可以在同一个地址替换内容，本指纹只证明本次解析看到的是这份字节。</p>
 *
 * <p>不可变：{@link BigDecimal} 与 {@link String} 都是不可变类型，记录本身没有可改动的视图。
 * {@code toString} 显式覆盖，只输出这三个字段——分片地址、{@code #EXT-X-KEY} 参数、清单原文都不进入模型，
 * 因此也不可能从 {@code toString} 泄漏出去。</p>
 *
 * @param durationSeconds 分片时长精确求和的候选秒数，必须大于零
 * @param segmentCount    计入求和的完整分片数，至少为 1
 * @param manifestSha256  原始字节（含换行）的 SHA-256，64 位小写十六进制
 */
public record HlsMediaDurationCandidate(BigDecimal durationSeconds, int segmentCount, String manifestSha256) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public HlsMediaDurationCandidate {
        if (durationSeconds == null || durationSeconds.signum() <= 0) {
            throw new IllegalArgumentException("durationSeconds 必须是正的秒数");
        }
        double seconds = durationSeconds.doubleValue();
        if (!Double.isFinite(seconds) || seconds <= 0) {
            throw new IllegalArgumentException("durationSeconds 转换后必须是有限正数");
        }
        if (segmentCount < 1) {
            throw new IllegalArgumentException("segmentCount 至少为 1");
        }
        if (manifestSha256 == null || !SHA256_HEX.matcher(manifestSha256).matches()) {
            throw new IllegalArgumentException("manifestSha256 必须是 64 位小写十六进制");
        }
    }

    /** 只含三个安全字段的固定摘要，见类注释。 */
    @Override
    public String toString() {
        return "HlsMediaDurationCandidate[durationSeconds=" + durationSeconds
                + ", segmentCount=" + segmentCount
                + ", manifestSha256=" + manifestSha256 + "]";
    }
}
