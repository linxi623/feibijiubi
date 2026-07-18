package com.feibijiubi.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoStatusOutbox {
    private Long id;
    private String eventId;
    private Integer aggregateId;
    private Long aggregateSequence;
    private String eventType;
    private String payload;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime sendingAt;
    private String leaseToken;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
}
