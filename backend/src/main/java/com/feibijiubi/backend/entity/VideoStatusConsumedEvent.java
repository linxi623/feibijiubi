package com.feibijiubi.backend.entity;

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
}
