package com.feibijiubi.backend.videostats.service.admin;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * T10-B1 consumed status3 受控重放专用行对象。
 *
 * <p>与 {@link VideoStatusFailureAdminRow} 的安全投影不同，这里的两份投影是重放协议内部使用的：
 * {@link ConsumedTarget} 需要 {@code payload_hash}/{@code delta} 证明消费账本身份，
 * {@link OutboxSource} 需要 {@code payload}（那是 relay 真正会发布的内容）。
 * 它们只在服务内部流转，<b>绝不</b>作为 HTTP 响应返回：对外只返回
 * {@code VideoStatusStatus3ReplayVO} 的安全字段。</p>
 */
public final class VideoStatusStatus3ReplayRow {

    private VideoStatusStatus3ReplayRow() {
    }

    /** 消费账本目标行：不含 payload 原文，只用 payload_hash/delta 证明语义身份。 */
    @Data
    public static class ConsumedTarget {
        private Long id;
        private String eventId;
        private Integer vid;
        private String eventType;
        private Long delta;
        private String payloadHash;
        private Integer processStatus;
        private Integer consumerRetryCount;
        private LocalDateTime consumedAt;
        private LocalDateTime redisAppliedAt;
        private Boolean lastErrorPresent;
        /** 由 DB 时钟计算的 consumed_at 年龄（秒），非数据库列。 */
        private Long consumedAgeSeconds;
    }

    /** Outbox 投递源行：relay 会用这里的 payload 重新投递同一 eventId。 */
    @Data
    public static class OutboxSource {
        private Long id;
        private String eventId;
        private Integer vid;
        private String eventType;
        private String payload;
        private Integer status;
        private LocalDateTime sentAt;
    }
}
