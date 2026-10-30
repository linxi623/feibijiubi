package com.feibijiubi.backend.videostats.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoStatusConsumedEvent {
    private Long id;
    private String eventId;
    private Integer vid;
    private String eventType;
    private Long delta;
    private String payload;
    private String payloadHash;
    private Integer processStatus;
    private String lastError;
    private Integer consumerRetryCount;
    private LocalDateTime consumedAt;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime redisAppliedAt;
    private LocalDateTime flushedAt;

    // T09 status0 补偿：与消费主流程隔离的领取租约/冷却字段。
    private Integer compensationAttempts;
    private LocalDateTime compensationNextAttemptAt;
    private String compensationLeaseToken;
    private LocalDateTime compensationLeaseUntil;
    private LocalDateTime lastCompensationAt;
    /** 只由补偿候选查询用 DB 时钟计算的 consumed_at 年龄（秒），非数据库列。 */
    private Long consumedAgeSeconds;
    /** 同一条查询返回的 DB 当前时间，避免用 JVM 时钟判断租约/冷却/静默期。 */
    private LocalDateTime databaseNow;
}
