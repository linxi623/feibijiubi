package com.feibijiubi.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoStatusConsumptionRepairLog {
    private Long id;
    private String operationId;
    private String eventId;
    private String action;
    private Integer operationStatus;
    private String reason;
    private String operator;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
