package com.feibijiubi.backend.common;

import java.util.List;

/**
 * T03：暂态延后刷库。
 *
 * <p>表示“当前无法立即证明该批次非法”，最典型的是负净增量遇到不足的数据库基线：
 * 依赖的正增量事件可能已经写入 Redis，但 {@code markRedisApplied} 的状态提交尚未完成，
 * 因此 flush 的候选集（{@code process_status = 1}）暂时看不到它。</p>
 *
 * <p>这类情况必须延后重试，而不是转人工修复（status 3）：延后不修改任何统计值，
 * 事件保持 {@code process_status = 1}，dirty 标记保留，下一次调度再试。
 * 只有“明确非法”（未知事件类型、统计行缺失、累计溢出）才允许
 * {@link VideoStatusFlushDataException} 走受控修复。</p>
 */
public class VideoStatusFlushDeferredException extends RuntimeException {

    private final Integer vid;
    private final List<Long> consumedEventIds;
    private final String reason;

    public VideoStatusFlushDeferredException(
            Integer vid,
            List<Long> consumedEventIds,
            String reason
    ) {
        super(reason);
        this.vid = vid;
        this.consumedEventIds = List.copyOf(consumedEventIds);
        this.reason = reason;
    }

    public Integer getVid() {
        return vid;
    }

    public List<Long> getConsumedEventIds() {
        return consumedEventIds;
    }

    public String getReason() {
        return reason;
    }
}
