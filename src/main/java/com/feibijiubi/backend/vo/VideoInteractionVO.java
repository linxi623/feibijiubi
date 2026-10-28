package com.feibijiubi.backend.vo;

/**
 * T15 第二阶段：播放/分享计数接口对外的唯一响应体。
 *
 * <p>只暴露三件事，其它一律不返回：</p>
 * <ul>
 *   <li>{@code counted}：这次（或这次重放所对应的原始请求）<b>真的计了一次数</b>；</li>
 *   <li>{@code duplicate}：{@code true} 表示命中了已存在的 {@code requestKey}，本次是幂等重放，
 *       <b>没有</b>再加一次计数；{@code counted} 描述的是原请求的结果，不是“这次又加了一”；</li>
 *   <li>{@code reason}：{@code COUNTED} 或 {@code WINDOW_SUPPRESSED}（滚动 30 分钟内已计过一次）。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>返回 {@code requestId}、{@code eventId}、{@code actorHash}、窗口时间或剩余额度：
 * 前两者是内部一致性标识（对外没有用处，只会被当成可观测的“成功凭证”），后两者可被用来枚举
 * 他人身份或探测限流边界。</p>
 */
public record VideoInteractionVO(
        boolean counted,
        boolean duplicate,
        String reason
) {
    /** 本次计了一次数。 */
    public static final String REASON_COUNTED = "COUNTED";
    /** 同一 actor + vid + action 在滚动 30 分钟窗口内已经计过一次，本次未计数。 */
    public static final String REASON_WINDOW_SUPPRESSED = "WINDOW_SUPPRESSED";

    public static VideoInteractionVO counted(boolean duplicate) {
        return new VideoInteractionVO(true, duplicate, REASON_COUNTED);
    }

    public static VideoInteractionVO windowSuppressed(boolean duplicate) {
        return new VideoInteractionVO(false, duplicate, REASON_WINDOW_SUPPRESSED);
    }
}
