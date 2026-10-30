package com.feibijiubi.backend.videostats.vo;

import com.feibijiubi.backend.videostats.metrics.VideoStatusBacklogSampler;
import com.feibijiubi.backend.videostats.metrics.VideoStatusTelemetry;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * T05 只读指标响应的聚合 VO。
 *
 * <p>两部分必须分开读：</p>
 * <ul>
 *   <li>{@link #backlog} 来自共享 MySQL，是<b>全局</b>积压（采样值，可能 stale）；</li>
 *   <li>{@link #instanceTelemetry} 只是<b>本实例</b>的 operation 计数与耗时，
 *       不跨实例求和。</li>
 * </ul>
 * 不含 eventId / vid 等无界维度，也不含密钥、连接串等敏感字段。
 */
@Data
@AllArgsConstructor
public class VideoStatusMetricsVO {
    /** 读法说明：本实例计数 + 共享 DB 积压，两者不可相加。 */
    private final String scopeNote;
    private final VideoStatusBacklogSampler.Snapshot backlog;
    private final VideoStatusTelemetry.Snapshot instanceTelemetry;
}
