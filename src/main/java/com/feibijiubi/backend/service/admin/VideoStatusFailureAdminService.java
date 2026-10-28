package com.feibijiubi.backend.service.admin;

import java.time.LocalDateTime;
import java.util.List;

/**
 * T10 失败事件只读诊断、“受审计确认已阅（ACKNOWLEDGE）”与 T10-B1 consumed status3 受控重放的
 * 预检查。确认只写审计，不修改事件/计数/Redis；重放本体在
 * {@link VideoStatusStatus3ReplayService}，本接口只负责把它“读写拒绝条件一致”的判定
 * 暴露给只读预检查。明确不实现 DLQ、批量重放、自动修复或强制重建。
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
                    Long consumedAgeSeconds, boolean canAcknowledge,
                    ReplayPrecheck replay, String note) {
    }

    /**
     * T10-B1 consumed status3 重放预检查（与 POST replay 共用同一套判定）。
     *
     * <p>只有 {@code process_status=3} 的目标才返回本结构；status0 仍是只读诊断。
     * {@code decision} 为 {@code PROVEN_REPLAY_ELIGIBLE} 或
     * {@code REFUSED_CONTROLLED_REPAIR_ONLY}，后者给出稳定 {@code refusalCode}。
     * 即使 {@code eligible=true} 也仍需管理员显式 POST：它不是一个 SAFE 结论，
     * 也不代表事件已修复。</p>
     */
    record ReplayPrecheck(String decision, boolean eligible, String refusalCode,
                          boolean redisAppliedEvidencePresent, boolean withinSafeWindow,
                          Long consumedAgeSeconds, Integer outboxStatus,
                          Long processedTtlSeconds, int replayAttempts, int maxReplayAttempts) {
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
