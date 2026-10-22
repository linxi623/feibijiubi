package com.feibijiubi.backend.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusConsumptionRepairLog;
import com.feibijiubi.backend.mapper.VideoStatusFailureAdminMapper;
import com.feibijiubi.backend.service.admin.VideoStatusFailureAdminRow.ConsumedRow;
import com.feibijiubi.backend.service.admin.VideoStatusFailureAdminRow.OutboxRow;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * T10 第一阶段实现：只读失败诊断 + 受审计确认已阅（ACKNOWLEDGE）。
 * status3 预检查故意保守：只返回 REQUIRES_CONTROLLED_REPAIR；redis_applied_at 为空
 * 不证明未应用，超龄也不伪造成 SAFE。确认只写审计，不修改目标状态/计数/Redis，
 * 不创建新 eventId；consumed status0 只读，不能确认。
 */
@Service
@RequiredArgsConstructor
public class VideoStatusFailureAdminServiceImpl implements VideoStatusFailureAdminService {

    static final String ACTION_ACKNOWLEDGE_FAILURE = "ACKNOWLEDGE_FAILURE";
    static final String ACKNOWLEDGE_METADATA_SCHEMA = "T10_ACKNOWLEDGE_META_V1";
    static final String SAFE_LAST_ERROR_HINT =
            "存在失败详情，但 HTTP 不返回 last_error 原文（可能含 payload/凭证片段）；请受控查库诊断";

    static final String REASON_OUTBOX_CONFIRM_WRITEBACK_BUDGET_EXHAUSTED =
            "OUTBOX_CONFIRM_WRITEBACK_BUDGET_EXHAUSTED";
    static final String REASON_OUTBOX_LEASE_RECOVERY_BUDGET_EXHAUSTED =
            "OUTBOX_LEASE_RECOVERY_BUDGET_EXHAUSTED";
    static final String REASON_OUTBOX_PUBLISH_RETRY_BUDGET_EXHAUSTED =
            "OUTBOX_PUBLISH_RETRY_BUDGET_EXHAUSTED";
    static final String REASON_OUTBOX_FAILED_WITH_ERROR_DETAIL =
            "OUTBOX_FAILED_WITH_ERROR_DETAIL";
    static final String REASON_OUTBOX_FAILED_NO_ERROR_DETAIL =
            "OUTBOX_FAILED_NO_ERROR_DETAIL";
    static final String REASON_CONSUMED_REPAIR_REQUIRED = "CONSUMED_REPAIR_REQUIRED";
    static final String REASON_CONSUMED_STATUS0_DIAGNOSTIC_ONLY =
            "CONSUMED_STATUS0_DIAGNOSTIC_ONLY";

    static final String PRECHECK_OUTBOX_CONSUMED_LEDGER_EXISTS =
            "OUTBOX_CONSUMED_LEDGER_EXISTS";
    static final String PRECHECK_OUTBOX_REQUIRES_CONTROLLED_REPAIR =
            "OUTBOX_REQUIRES_CONTROLLED_REPAIR";
    static final String PRECHECK_CONSUMED_REDIS_APPLIED_EVIDENCE_MISSING =
            "CONSUMED_REDIS_APPLIED_EVIDENCE_MISSING";
    static final String PRECHECK_CONSUMED_OVER_SAFE_WINDOW =
            "CONSUMED_OVER_SAFE_WINDOW";
    static final String PRECHECK_CONSUMED_REDIS_EVIDENCE_MISSING_AND_OVER_SAFE_WINDOW =
            "CONSUMED_REDIS_EVIDENCE_MISSING_AND_OVER_SAFE_WINDOW";
    static final String PRECHECK_CONSUMED_REQUIRES_CONTROLLED_REPAIR =
            "CONSUMED_REQUIRES_CONTROLLED_REPAIR";
    static final String PRECHECK_CONSUMED_STATUS0_EVIDENCE_UNKNOWN =
            "CONSUMED_STATUS0_EVIDENCE_UNKNOWN";

    private static final int OUTBOX_FAILED = 3;
    private static final int OUTBOX_RETRY_BUDGET = 10;
    private static final int OUTBOX_LEASE_RECOVERY_BUDGET = 10;
    private static final int OUTBOX_CONFIRM_WRITEBACK_BUDGET = 10;
    private static final int CONSUMED_RECEIVED = 0;
    private static final int CONSUMED_REPAIR_REQUIRED = 3;

    private final VideoStatusFailureAdminMapper mapper;
    private final VideoStatusProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Page list(String source, long afterId, int limit) {
        String normalized = requireSource(source);
        requirePaging(afterId, limit);
        List<FailureItem> items = new ArrayList<>();
        boolean hasMore;
        Long nextAfterId = null;
        if (SOURCE_OUTBOX.equals(normalized)) {
            List<OutboxRow> rows = mapper.selectFailedOutbox(afterId, limit + 1);
            hasMore = rows.size() > limit;
            for (int i = 0; i < Math.min(rows.size(), limit); i++) {
                items.add(outboxItem(rows.get(i)));
            }
            if (hasMore) {
                nextAfterId = items.get(items.size() - 1).id();
            }
        } else {
            List<ConsumedRow> rows = mapper.selectDiagnosableConsumed(afterId, limit + 1);
            hasMore = rows.size() > limit;
            for (int i = 0; i < Math.min(rows.size(), limit); i++) {
                items.add(consumedItem(rows.get(i)));
            }
            if (hasMore) {
                nextAfterId = items.get(items.size() - 1).id();
            }
        }
        return new Page(normalized, afterId, limit, hasMore, nextAfterId, items);
    }

    @Override
    @Transactional(readOnly = true)
    public Detail detail(String source, String eventId) {
        String normalized = requireSource(source);
        requireEventId(eventId);
        if (SOURCE_OUTBOX.equals(normalized)) {
            OutboxRow row = mapper.selectFailedOutboxByEventId(eventId);
            if (row == null) {
                throw notFound(source, eventId);
            }
            return new Detail(outboxItem(row), precheckOutbox(row),
                    "只读详情：不返回 payload/leaseToken/last_error 原文；status3 预检查不会给出 SAFE。");
        }
        ConsumedRow row = mapper.selectDiagnosableConsumedByEventId(eventId);
        if (row == null) {
            throw notFound(source, eventId);
        }
        return new Detail(consumedItem(row), precheckConsumed(row),
                "只读详情：status0 仅用于诊断且不能确认；status3 必须走受控修复，"
                        + "本接口不改状态、不新建 eventId。");
    }

    @Override
    @Transactional(readOnly = true)
    public Precheck precheck(String source, String eventId) {
        String normalized = requireSource(source);
        requireEventId(eventId);
        if (SOURCE_OUTBOX.equals(normalized)) {
            OutboxRow row = mapper.selectFailedOutboxByEventId(eventId);
            if (row == null) {
                throw notFound(source, eventId);
            }
            return precheckOutbox(row);
        }
        ConsumedRow row = mapper.selectDiagnosableConsumedByEventId(eventId);
        if (row == null) {
            throw notFound(source, eventId);
        }
        return precheckConsumed(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AcknowledgeResult acknowledge(String source, String eventId, String operationId,
                                         String reason, String operator) {
        String normalized = requireSource(source);
        requireEventId(eventId);
        requireAuditFields(operationId, reason, operator);

        VideoStatusConsumptionRepairLog existing =
                mapper.selectRepairLogByOperationId(operationId);
        if (existing != null) {
            AcknowledgeAuditMetadata metadata = requireSameAcknowledgeOperation(
                    existing, normalized, eventId, reason, operator);
            return new AcknowledgeResult(existing.getOperationId(), existing.getAction(),
                    metadata.source(), existing.getEventId(), existing.getOperator(),
                    "COMPLETED", existing.getCompletedAt(), true, false,
                    metadata.statusBefore(), metadata.statusAfter(),
                    metadata.processStatusBefore(), metadata.processStatusAfter(),
                    "operationId 已完成过，本次未重复写审计；确认已阅只是审计标记，"
                            + "目标状态/计数/Redis 从未被本接口修改。");
        }

        Integer statusBefore = null;
        Integer statusAfter = null;
        Integer processStatusBefore = null;
        Integer processStatusAfter = null;
        if (SOURCE_OUTBOX.equals(normalized)) {
            OutboxRow row = mapper.selectFailedOutboxByEventIdForUpdate(eventId);
            if (row == null) {
                throw notFound(source, eventId);
            }
            if (!Integer.valueOf(OUTBOX_FAILED).equals(row.getStatus())) {
                throw new BusinessException(409, "目标不是 Outbox FAILED（status=3），拒绝确认");
            }
            statusBefore = row.getStatus();
            statusAfter = row.getStatus();
        } else {
            ConsumedRow row = mapper.selectConsumedByEventIdForUpdate(eventId);
            if (row == null) {
                throw notFound(source, eventId);
            }
            int processStatus = row.getProcessStatus() == null ? -1 : row.getProcessStatus();
            if (processStatus == CONSUMED_RECEIVED) {
                throw new BusinessException(409,
                        "consumed status0 只读，不能确认；仅 status3（REPAIR_REQUIRED）可确认");
            }
            if (processStatus != CONSUMED_REPAIR_REQUIRED) {
                throw new BusinessException(409,
                        "目标不是 consumed status3（REPAIR_REQUIRED），拒绝确认");
            }
            processStatusBefore = row.getProcessStatus();
            processStatusAfter = row.getProcessStatus();
        }

        VideoStatusConsumptionRepairLog log = new VideoStatusConsumptionRepairLog();
        log.setOperationId(operationId);
        log.setEventId(eventId);
        log.setAction(ACTION_ACKNOWLEDGE_FAILURE);
        log.setReason(reason);
        log.setOperator(operator);
        log.setLastError(acknowledgeMetadataJson(normalized, statusBefore, statusAfter,
                processStatusBefore, processStatusAfter));
        int inserted;
        try {
            inserted = mapper.insertCompletedRepairLog(log);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409,
                    "审计行唯一键冲突（operationId/eventId 已存在），本次确认整体回滚，"
                            + "未重复写审计；请用 GET 查询后换 new operationId");
        }
        if (inserted != 1) {
            throw new BusinessException(500,
                    "审计写入影响行数异常（" + inserted + "），本次确认整体回滚");
        }

        return new AcknowledgeResult(operationId, ACTION_ACKNOWLEDGE_FAILURE, normalized, eventId,
                operator, "COMPLETED", LocalDateTime.now(), false, true,
                statusBefore, statusAfter, processStatusBefore, processStatusAfter,
                "已写入确认已阅审计；before/after 状态相同，未修改目标状态/计数/Redis，"
                        + "未删除 payload 或失败证据。该动作不是 status3 重放，也不是对账。");
    }

    private FailureItem outboxItem(OutboxRow row) {
        boolean present = Boolean.TRUE.equals(row.getLastErrorPresent());
        return new FailureItem(SOURCE_OUTBOX, row.getId(), row.getEventId(), row.getVid(),
                row.getEventType(), row.getStatus(), outboxStatusName(row.getStatus()),
                outboxReason(row), present, present ? SAFE_LAST_ERROR_HINT : null,
                row.getRetryCount(), null, row.getLeaseRecoveryCount(),
                row.getConfirmWritebackCount(), row.getCreatedAt(), null, null, null, null);
    }

    private FailureItem consumedItem(ConsumedRow row) {
        boolean present = Boolean.TRUE.equals(row.getLastErrorPresent());
        int status = row.getProcessStatus() == null ? -1 : row.getProcessStatus();
        return new FailureItem(SOURCE_CONSUMED, row.getId(), row.getEventId(), row.getVid(),
                row.getEventType(), row.getProcessStatus(), consumedStatusName(status),
                status == CONSUMED_REPAIR_REQUIRED
                        ? REASON_CONSUMED_REPAIR_REQUIRED
                        : REASON_CONSUMED_STATUS0_DIAGNOSTIC_ONLY,
                present, present ? SAFE_LAST_ERROR_HINT : null,
                null, row.getConsumerRetryCount(), null, null, null,
                row.getConsumedAt(), row.getLastAttemptAt(), row.getRedisAppliedAt(),
                row.getFlushedAt());
    }

    private static String outboxReason(OutboxRow row) {
        int confirm = row.getConfirmWritebackCount() == null ? 0 : row.getConfirmWritebackCount();
        int lease = row.getLeaseRecoveryCount() == null ? 0 : row.getLeaseRecoveryCount();
        int retry = row.getRetryCount() == null ? 0 : row.getRetryCount();
        if (confirm >= OUTBOX_CONFIRM_WRITEBACK_BUDGET) {
            return REASON_OUTBOX_CONFIRM_WRITEBACK_BUDGET_EXHAUSTED;
        }
        if (lease >= OUTBOX_LEASE_RECOVERY_BUDGET) {
            return REASON_OUTBOX_LEASE_RECOVERY_BUDGET_EXHAUSTED;
        }
        if (retry >= OUTBOX_RETRY_BUDGET) {
            return REASON_OUTBOX_PUBLISH_RETRY_BUDGET_EXHAUSTED;
        }
        return Boolean.TRUE.equals(row.getLastErrorPresent())
                ? REASON_OUTBOX_FAILED_WITH_ERROR_DETAIL
                : REASON_OUTBOX_FAILED_NO_ERROR_DETAIL;
    }

    private Precheck precheckOutbox(OutboxRow row) {
        Integer consumed = mapper.selectConsumedProcessStatus(row.getEventId());
        String reason = consumed != null
                ? PRECHECK_OUTBOX_CONSUMED_LEDGER_EXISTS
                : PRECHECK_OUTBOX_REQUIRES_CONTROLLED_REPAIR;
        return new Precheck(SOURCE_OUTBOX, row.getEventId(),
                DECISION_REQUIRES_CONTROLLED_REPAIR, reason, false, false, null, true,
                "Outbox FAILED 的 T07 单事件重放是另一条极窄路径；本 T10 预检查不认证 SAFE。 "
                        + "已存在 consumed 账本、或无法证明窗口/账本边界时，必须进入受控修复。"
                        + "确认已阅只写审计，不重放、不改状态。");
    }

    private Precheck precheckConsumed(ConsumedRow row) {
        int status = row.getProcessStatus() == null ? -1 : row.getProcessStatus();
        Long age = row.getConsumedAgeSeconds();
        long maxAge = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        boolean withinSafeWindow = age != null && age >= 0 && age < maxAge;
        boolean redisEvidence = row.getRedisAppliedAt() != null;
        if (status == CONSUMED_RECEIVED) {
            return new Precheck(SOURCE_CONSUMED, row.getEventId(),
                    DECISION_DIAGNOSTIC_ONLY, PRECHECK_CONSUMED_STATUS0_EVIDENCE_UNKNOWN,
                    redisEvidence, withinSafeWindow, age, false,
                    "status0 仅用于诊断且不能确认：本接口不领取、不重发、不转 status3。"
                            + "redis_applied_at 为空不能证明未应用，需 T09/T10/T12 继续核对。");
        }
        String reason;
        if (!redisEvidence && !withinSafeWindow) {
            reason = PRECHECK_CONSUMED_REDIS_EVIDENCE_MISSING_AND_OVER_SAFE_WINDOW;
        } else if (!redisEvidence) {
            reason = PRECHECK_CONSUMED_REDIS_APPLIED_EVIDENCE_MISSING;
        } else if (!withinSafeWindow) {
            reason = PRECHECK_CONSUMED_OVER_SAFE_WINDOW;
        } else {
            reason = PRECHECK_CONSUMED_REQUIRES_CONTROLLED_REPAIR;
        }
        return new Precheck(SOURCE_CONSUMED, row.getEventId(),
                DECISION_REQUIRES_CONTROLLED_REPAIR, reason, redisEvidence,
                withinSafeWindow, age, true,
                "status3 预检查不会返回 SAFE：redis_applied_at 为空不证明未应用，"
                        + "超龄也不等于安全。本阶段不修改 process_status、不创建新 eventId、"
                        + "不自动重放；确认已阅只写审计，受控修复/对账仍需后续任务。");
    }

    private AcknowledgeAuditMetadata requireSameAcknowledgeOperation(
            VideoStatusConsumptionRepairLog existing, String source, String eventId,
            String reason, String operator) {
        boolean sameShape = Integer.valueOf(1).equals(existing.getOperationStatus())
                && ACTION_ACKNOWLEDGE_FAILURE.equals(existing.getAction())
                && eventId.equals(existing.getEventId())
                && Objects.equals(operator, existing.getOperator());
        if (!sameShape) {
            throw new BusinessException(409,
                    "operationId 已被其他处置请求使用（目标/动作/操作人不一致），拒绝复用");
        }
        if (!Objects.equals(reason, existing.getReason())) {
            throw new BusinessException(409,
                    "operationId 已完成，但本次 reason 与已记录原因不一致，拒绝伪装成原结果");
        }
        AcknowledgeAuditMetadata metadata = parseAcknowledgeMetadata(existing.getLastError());
        if (metadata == null || !source.equals(metadata.source())) {
            throw new BusinessException(409,
                    "operationId 已存在，但审计元数据无法证明是同一 source 的确认操作，拒绝复用");
        }
        if (!Objects.equals(metadata.statusBefore(), metadata.statusAfter())
                || !Objects.equals(metadata.processStatusBefore(), metadata.processStatusAfter())) {
            throw new BusinessException(409,
                    "operationId 审计元数据的 before/after 不一致，拒绝按确认操作复用");
        }
        return metadata;
    }

    private String acknowledgeMetadataJson(String source, Integer statusBefore,
                                           Integer statusAfter, Integer processStatusBefore,
                                           Integer processStatusAfter) {
        AcknowledgeAuditMetadata metadata = new AcknowledgeAuditMetadata(
                ACKNOWLEDGE_METADATA_SCHEMA, source, statusBefore, statusAfter,
                processStatusBefore, processStatusAfter);
        try {
            String json = objectMapper.writeValueAsString(metadata);
            if (json.length() > 1000) {
                throw new BusinessException(500,
                        "确认审计元数据超过 last_error 列长度，本次确认整体回滚");
            }
            return json;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500,
                    "确认审计元数据序列化失败，本次确认整体回滚");
        }
    }

    private AcknowledgeAuditMetadata parseAcknowledgeMetadata(String lastError) {
        if (lastError == null || !lastError.contains(ACKNOWLEDGE_METADATA_SCHEMA)) {
            return null;
        }
        try {
            AcknowledgeAuditMetadata metadata =
                    objectMapper.readValue(lastError, AcknowledgeAuditMetadata.class);
            if (!ACKNOWLEDGE_METADATA_SCHEMA.equals(metadata.schema()) || metadata.source() == null) {
                return null;
            }
            return metadata;
        } catch (Exception e) {
            return null;
        }
    }

    private static String outboxStatusName(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case 0 -> "PENDING";
            case 1 -> "SENDING";
            case 2 -> "SENT";
            case 3 -> "FAILED";
            default -> "UNKNOWN(" + status + ")";
        };
    }

    private static String consumedStatusName(int status) {
        return switch (status) {
            case 0 -> "RECEIVED";
            case 1 -> "REDIS_APPLIED_PENDING_FLUSH";
            case 2 -> "FLUSHED";
            case 3 -> "REPAIR_REQUIRED";
            default -> "UNKNOWN(" + status + ")";
        };
    }

    private static BusinessException notFound(String source, String eventId) {
        return new BusinessException(404,
                "没有找到 source=" + source + "、eventId=" + eventId
                        + " 的可诊断失败事件（Outbox 仅 status=3；Consumed 仅 status0/3）");
    }

    private static String requireSource(String source) {
        if (source == null || source.isBlank()) {
            throw new BusinessException(400, "source 必须为 OUTBOX 或 CONSUMED");
        }
        String normalized = source.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SOURCE_OUTBOX.equals(normalized) && !SOURCE_CONSUMED.equals(normalized)) {
            throw new BusinessException(400, "source 必须为 OUTBOX 或 CONSUMED");
        }
        return normalized;
    }

    private static void requirePaging(long afterId, int limit) {
        if (afterId < 0L) {
            throw new BusinessException(400, "afterId 必须 >= 0");
        }
        if (limit < 1 || limit > 100) {
            throw new BusinessException(400, "limit 必须在 1 到 100 之间");
        }
    }

    private static void requireEventId(String eventId) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 64) {
            throw new BusinessException(400, "eventId 不合法（必填，≤64）");
        }
    }

    private static void requireAuditFields(String operationId, String reason, String operator) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 64) {
            throw new BusinessException(400, "operationId 不合法（必填，≤64）");
        }
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new BusinessException(400, "reason 不合法（必填，≤1000）");
        }
        if (operator == null || operator.isBlank() || operator.length() > 64) {
            throw new BusinessException(401, "操作人只能来自登录态 currentUserId，缺失时拒绝执行");
        }
    }

    record AcknowledgeAuditMetadata(String schema, String source, Integer statusBefore,
                                    Integer statusAfter, Integer processStatusBefore,
                                    Integer processStatusAfter) {
    }
}
