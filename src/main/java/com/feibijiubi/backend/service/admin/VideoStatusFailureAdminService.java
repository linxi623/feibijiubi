package com.feibijiubi.backend.service.admin;

import java.time.LocalDateTime;
import java.util.List;

/**
 * T10 第一阶段：失败事件只读诊断与“受审计确认已阅（ACKNOWLEDGE）”。
 * 确认只写审计，不修改事件/计数/Redis；明确不实现 DLQ、status3 自动重放、批量重放、
 * 对账或强制重建。仅 Outbox FAILED(status=3) 与 consumed status3 可确认；status0 只读。
 */
public interface VideoStatusFailureAdminService {

    String SOURCE_OUTBOX = "OUTBOX";
    String SOURCE_CONSUMED = "CONSUMED";
    String DECISION_REQUIRES_CONTROLLED_REPAIR = "REQUIRES_CONTROLLED_REPAIR";
    String DECISION_DIAGNOSTIC_ONLY = "DIAGNOSTIC_ONLY_EVIDENCE_UNKNOWN";
    String ACTION_ACKNOWLEDGE_FAILURE = "ACKNOWLEDGE_FAILURE";

    record FailureItem(
            String source, Long id, String eventId, Integer vid, String eventType,
            Integer status, String statusName, String reasonCode,
            Boolean lastErrorPresent, String lastErrorHint,
            Integer retryCount, Integer consumerRetryCount,
            Integer leaseRecoveryCount, Integer confirmWritebackCount,
            LocalDateTime createdAt, LocalDateTime consumedAt,
            LocalDateTime lastAttemptAt, LocalDateTime redisAppliedAt,
            LocalDateTime flushedAt) {
    }

    record Page(String source, long afterId, int limit, boolean hasMore,
                Long nextAfterId, List<FailureItem> items) {
    }

    record Precheck(String source, String eventId, String decision, String reasonCode,
                    boolean redisAppliedEvidencePresent, boolean withinSafeWindow,
                    Long consumedAgeSeconds, boolean canAcknowledge, String note) {
    }

    record Detail(FailureItem item, Precheck precheck, String note) {
    }

    record AcknowledgeResult(String operationId, String action, String source, String eventId,
                             String operator, String operationStatus, LocalDateTime completedAt,
                             boolean duplicate, boolean acknowledgedApplied, Integer statusBefore,
                             Integer statusAfter, Integer processStatusBefore,
                             Integer processStatusAfter, String message) {
    }

    Page list(String source, long afterId, int limit);

    Detail detail(String source, String eventId);

    Precheck precheck(String source, String eventId);

    AcknowledgeResult acknowledge(String source, String eventId, String operationId,
                                  String reason, String operator);
}
