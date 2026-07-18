package com.feibijiubi.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoStatusConsumedEvent {
    private Long id;
    private String eventId;
    private Integer vid;
    private Long aggregateSequence;
    private String eventType;
    private Long delta;
    private String payloadHash;
    private Integer processStatus;
    private String lastError;
    private LocalDateTime consumedAt;
    private LocalDateTime committedAt;
}