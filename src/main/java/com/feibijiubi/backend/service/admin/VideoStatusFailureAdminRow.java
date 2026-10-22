package com.feibijiubi.backend.service.admin;

import lombok.Data;

import java.time.LocalDateTime;

/** 失败处置专用安全行对象：不包含 payload、lease_token、last_error 原文。 */
public final class VideoStatusFailureAdminRow {

    private VideoStatusFailureAdminRow() {
    }

    @Data
    public static class OutboxRow {
        private Long id;
        private String eventId;
        private Integer vid;
        private String eventType;
        private Integer status;
        private Integer retryCount;
        private Integer leaseRecoveryCount;
        private Integer confirmWritebackCount;
        private LocalDateTime createdAt;
        private LocalDateTime nextRetryAt;
        private LocalDateTime sendingAt;
        private LocalDateTime sentAt;
        private Boolean lastErrorPresent;
    }

    @Data
    public static class ConsumedRow {
        private Long id;
        private String eventId;
        private Integer vid;
        private String eventType;
        private Integer processStatus;
        private Integer consumerRetryCount;
        private LocalDateTime consumedAt;
        private LocalDateTime lastAttemptAt;
        private LocalDateTime redisAppliedAt;
        private LocalDateTime flushedAt;
        private Boolean lastErrorPresent;
        private Long consumedAgeSeconds;
    }
}