package com.feibijiubi.backend.videostats.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.entity.VideoStatusConsumptionRepairLog;
import com.feibijiubi.backend.videostats.mapper.VideoStatusFailureAdminMapper;
import com.feibijiubi.backend.videostats.service.admin.VideoStatusFailureAdminRow.ConsumedRow;
import com.feibijiubi.backend.videostats.service.admin.VideoStatusFailureAdminRow.OutboxRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T10 第一阶段纯单元测试：不连 MySQL/Redis/MQ，不启动 Spring。 */
class VideoStatusFailureAdminServiceTests {

    private VideoStatusFailureAdminMapper mapper;
    private VideoStatusFailureAdminServiceImpl service;
    private ObjectMapper objectMapper;
    private VideoStatusStatus3ReplayService status3ReplayService;

    @BeforeEach
    void setUp() {
        mapper = mock(VideoStatusFailureAdminMapper.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setConsumerRecoveryAutoReplayMaxAgeSeconds(600);
        // status3 重放判定有自己的专用单元测试；这里只验证预检查把它透传。
        status3ReplayService = mock(VideoStatusStatus3ReplayService.class);
        service = new VideoStatusFailureAdminServiceImpl(
                mapper, properties, objectMapper, status3ReplayService);
        when(mapper.selectIndexColumns(anyString(), anyString())).thenAnswer(invocation -> {
            String index = invocation.getArgument(1);
            if (VideoStatusFailureAdminServiceImpl.OUTBOX_FAILURE_LIST_INDEX.equals(index)) {
                return List.of("status", "id");
            }
            if (VideoStatusFailureAdminServiceImpl.CONSUMED_FAILURE_LIST_INDEX.equals(index)) {
                return List.of("process_status", "id");
            }
            return List.of();
        });
    }

    @Test
    void outboxListUsesBoundedKeysetPaginationAndSafeReasonCodes() {
        OutboxRow first = outboxRow(1L, "e1", 10, 0, 0);
        OutboxRow second = outboxRow(2L, "e2", 0, 10, 0);
        OutboxRow extra = outboxRow(3L, "e3", 0, 0, 10);
        when(mapper.selectFailedOutbox(7L, 3)).thenReturn(List.of(first, second, extra));

        VideoStatusFailureAdminService.Page page = service.list("outbox", 7L, 2);

        assertEquals(2, page.items().size());
        assertTrue(page.hasMore());
        assertEquals(2L, page.nextAfterId());
        assertEquals(VideoStatusFailureAdminServiceImpl.REASON_OUTBOX_PUBLISH_RETRY_BUDGET_EXHAUSTED,
                page.items().get(0).reasonCode());
        assertEquals(VideoStatusFailureAdminServiceImpl.REASON_OUTBOX_LEASE_RECOVERY_BUDGET_EXHAUSTED,
                page.items().get(1).reasonCode());
        assertEquals("FAILED", page.items().get(0).statusName());
        assertEquals("OUTBOX", page.source());
        verify(mapper).selectFailedOutbox(7L, 3);
    }

    @Test
    void consumedListIncludesStatus0ForDiagnosisAndStatus3ForRepair() {
        ConsumedRow repair = consumedRow(4L, "e4", 3, null, 120L);
        ConsumedRow received = consumedRow(5L, "e5", 0, null, 30L);
        when(mapper.selectDiagnosableConsumed(0L, 3)).thenReturn(List.of(repair, received, repair));

        VideoStatusFailureAdminService.Page page = service.list("CONSUMED", 0L, 2);

        assertEquals(2, page.items().size());
        assertTrue(page.hasMore());
        assertEquals(5L, page.nextAfterId());
        assertEquals(VideoStatusFailureAdminServiceImpl.REASON_CONSUMED_REPAIR_REQUIRED,
                page.items().get(0).reasonCode());
        assertEquals("REPAIR_REQUIRED", page.items().get(0).statusName());
        assertEquals(VideoStatusFailureAdminServiceImpl.REASON_CONSUMED_STATUS0_DIAGNOSTIC_ONLY,
                page.items().get(1).reasonCode());
        assertEquals("RECEIVED", page.items().get(1).statusName());
    }

    @Test
    void status3WithoutRedisAppliedEvidenceIsNotSafe() {
        ConsumedRow row = consumedRow(8L, "e8", 3, null, 10L);
        when(mapper.selectDiagnosableConsumedByEventId("e8")).thenReturn(row);

        VideoStatusFailureAdminService.Precheck precheck = service.precheck("CONSUMED", "e8");

        assertEquals(VideoStatusFailureAdminServiceImpl.DECISION_REQUIRES_CONTROLLED_REPAIR,
                precheck.decision());
        assertEquals(VideoStatusFailureAdminServiceImpl
                        .PRECHECK_CONSUMED_REDIS_APPLIED_EVIDENCE_MISSING,
                precheck.reasonCode());
        assertFalse(precheck.redisAppliedEvidencePresent());
        assertTrue(precheck.withinSafeWindow());
        assertTrue(precheck.canAcknowledge());
        assertFalse(precheck.decision().contains("SAFE"));
    }

    @Test
    void overAgeStatus3IsStillRequiresControlledRepairNotSafe() {
        ConsumedRow row = consumedRow(9L, "e9", 3, LocalDateTime.now(), 9999L);
        when(mapper.selectDiagnosableConsumedByEventId("e9")).thenReturn(row);

        VideoStatusFailureAdminService.Precheck precheck = service.precheck("CONSUMED", "e9");

        assertEquals(VideoStatusFailureAdminServiceImpl.DECISION_REQUIRES_CONTROLLED_REPAIR,
                precheck.decision());
        assertEquals(VideoStatusFailureAdminServiceImpl.PRECHECK_CONSUMED_OVER_SAFE_WINDOW,
                precheck.reasonCode());
        assertTrue(precheck.redisAppliedEvidencePresent());
        assertFalse(precheck.withinSafeWindow());
        assertTrue(precheck.canAcknowledge());
    }

    @Test
    void missingRedisEvidenceAndOverAgeReportsBothFactsButNeverSafe() {
        ConsumedRow row = consumedRow(10L, "e10", 3, null, 9999L);
        when(mapper.selectDiagnosableConsumedByEventId("e10")).thenReturn(row);

        VideoStatusFailureAdminService.Precheck precheck = service.precheck("CONSUMED", "e10");

        assertEquals(VideoStatusFailureAdminServiceImpl
                        .PRECHECK_CONSUMED_REDIS_EVIDENCE_MISSING_AND_OVER_SAFE_WINDOW,
                precheck.reasonCode());
        assertEquals(VideoStatusFailureAdminServiceImpl.DECISION_REQUIRES_CONTROLLED_REPAIR,
                precheck.decision());
    }

    @Test
    void status0IsReadOnlyDiagnosticAndCannotBeAcknowledged() {
        ConsumedRow row = consumedRow(6L, "e6", 0, null, 30L);
        when(mapper.selectDiagnosableConsumedByEventId("e6")).thenReturn(row);

        VideoStatusFailureAdminService.Precheck precheck = service.precheck("CONSUMED", "e6");

        assertEquals(VideoStatusFailureAdminServiceImpl.DECISION_DIAGNOSTIC_ONLY,
                precheck.decision());
        assertEquals(VideoStatusFailureAdminServiceImpl.PRECHECK_CONSUMED_STATUS0_EVIDENCE_UNKNOWN,
                precheck.reasonCode());
        assertFalse(precheck.canAcknowledge(),
                "status0 只读，不能通过确认接口改变处置状态");
        assertNull(precheck.replay(), "status0 不适用 status3 重放预检查");
        verify(status3ReplayService, never()).precheckStatus3Replay(anyString());
    }

    @Test
    void status3PrecheckPropagatesTheSharedReplayEvaluation() {
        ConsumedRow row = consumedRow(12L, "e12", 3, null, 10L);
        when(mapper.selectDiagnosableConsumedByEventId("e12")).thenReturn(row);
        VideoStatusFailureAdminService.ReplayPrecheck refusal =
                new VideoStatusFailureAdminService.ReplayPrecheck(
                        "REFUSED_CONTROLLED_REPAIR_ONLY", false,
                        "CONSUMED_PROCESSED_EVIDENCE_MISSING", false, true, 10L, 2, -2L, 0, 3);
        when(status3ReplayService.precheckStatus3Replay("e12")).thenReturn(refusal);

        VideoStatusFailureAdminService.Precheck precheck = service.precheck("CONSUMED", "e12");

        assertEquals(refusal, precheck.replay(),
                "预检查必须原样透传与 POST replay 共用的判定结果");
        assertEquals(3, precheck.replay().maxReplayAttempts());
        assertFalse(precheck.replay().eligible());
        // 即使重放判定拒绝，status3 仍可确认已阅：两者是不同动作。
        assertTrue(precheck.canAcknowledge());
    }

    @Test
    void outboxStatus3PrecheckAlsoRefusesToCertifySafeButCanBeAcknowledged() {
        when(mapper.selectFailedOutboxByEventId("e11")).thenReturn(outboxRow(11L, "e11", 10, 0, 0));
        when(mapper.selectConsumedProcessStatus("e11")).thenReturn(null);

        VideoStatusFailureAdminService.Precheck precheck = service.precheck("OUTBOX", "e11");

        assertEquals(VideoStatusFailureAdminServiceImpl.DECISION_REQUIRES_CONTROLLED_REPAIR,
                precheck.decision());
        assertEquals(VideoStatusFailureAdminServiceImpl.PRECHECK_OUTBOX_REQUIRES_CONTROLLED_REPAIR,
                precheck.reasonCode());
        assertTrue(precheck.canAcknowledge());
    }

    @Test
    void outboxAcknowledgeWritesOnlyCompletedAuditWithSameBeforeAndAfter() {
        when(mapper.selectRepairLogByOperationId("op-1")).thenReturn(null, completedAudit(
                "op-1", "e12", "确认已阅并留待对账", "42", "OUTBOX", 3, 3, null, null,
                LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
        when(mapper.selectRepairLogByOperationIdForUpdate("op-1")).thenReturn(null);
        when(mapper.selectFailedOutboxByEventIdForUpdate("e12"))
                .thenReturn(outboxRow(12L, "e12", 10, 0, 0));
        when(mapper.insertCompletedRepairLog(any())).thenReturn(1);

        VideoStatusFailureAdminService.AcknowledgeResult result =
                service.acknowledge("OUTBOX", "e12", "op-1", "确认已阅并留待对账", "42");

        assertTrue(result.acknowledgedApplied());
        assertFalse(result.duplicate());
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), result.completedAt(),
                "首次 completedAt 必须来自 DB 写入后回读，而不是 JVM now");
        assertEquals(3, result.statusBefore());
        assertEquals(3, result.statusAfter());
        assertNull(result.processStatusBefore());
        assertNull(result.processStatusAfter());
        assertEquals("ACKNOWLEDGE_FAILURE", result.action());
        assertEquals("42", result.operator());

        ArgumentCaptor<VideoStatusConsumptionRepairLog> captor =
                ArgumentCaptor.forClass(VideoStatusConsumptionRepairLog.class);
        verify(mapper).insertCompletedRepairLog(captor.capture());
        VideoStatusConsumptionRepairLog log = captor.getValue();
        assertEquals("ACKNOWLEDGE_FAILURE", log.getAction());
        assertEquals("确认已阅并留待对账", log.getReason());
        assertEquals("42", log.getOperator());
        assertTrue(log.getLastError().contains("T10_ACKNOWLEDGE_META_V1"));
        assertTrue(log.getLastError().contains("OUTBOX"));
        assertTrue(log.getLastError().contains("\"statusBefore\":3"));
        assertTrue(log.getLastError().contains("\"statusAfter\":3"));
        assertFalse(log.getLastError().contains("payload"));
        assertFalse(log.getLastError().contains("leaseToken"));
        assertFalse(log.getLastError().contains("last_error"));
    }

    @Test
    void consumedStatus3AcknowledgeKeepsBeforeAndAfterEqual() {
        when(mapper.selectRepairLogByOperationId("op-c3")).thenReturn(null, completedAudit(
                "op-c3", "e13", "确认已阅 status3", "42", "CONSUMED", null, null, 3, 3,
                LocalDateTime.of(2026, 1, 2, 3, 4, 6)));
        when(mapper.selectRepairLogByOperationIdForUpdate("op-c3")).thenReturn(null);
        when(mapper.selectConsumedByEventIdForUpdate("e13"))
                .thenReturn(consumedRow(13L, "e13", 3, LocalDateTime.now(), 30L));
        when(mapper.insertCompletedRepairLog(any())).thenReturn(1);

        VideoStatusFailureAdminService.AcknowledgeResult result =
                service.acknowledge("CONSUMED", "e13", "op-c3", "确认已阅 status3", "42");

        assertTrue(result.acknowledgedApplied());
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 6), result.completedAt());
        assertEquals(3, result.processStatusBefore());
        assertEquals(3, result.processStatusAfter());
        assertNull(result.statusBefore());
        assertNull(result.statusAfter());
        ArgumentCaptor<VideoStatusConsumptionRepairLog> captor =
                ArgumentCaptor.forClass(VideoStatusConsumptionRepairLog.class);
        verify(mapper).insertCompletedRepairLog(captor.capture());
        assertTrue(captor.getValue().getLastError().contains("\"processStatusBefore\":3"));
        assertTrue(captor.getValue().getLastError().contains("\"processStatusAfter\":3"));
    }

    @Test
    void consumedStatus0AcknowledgeIsRejectedWithoutWritingAudit() {
        when(mapper.selectRepairLogByOperationId("op-c0")).thenReturn(null);
        when(mapper.selectConsumedByEventIdForUpdate("e14"))
                .thenReturn(consumedRow(14L, "e14", 0, null, 30L));

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.acknowledge("CONSUMED", "e14", "op-c0", "试图确认 status0", "42"));

        assertEquals(409, rejected.getCode());
        assertTrue(rejected.getMessage().contains("status0"));
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void consumedStatus1AcknowledgeIsRejectedWithoutWritingAudit() {
        when(mapper.selectRepairLogByOperationId("op-c1")).thenReturn(null);
        when(mapper.selectConsumedByEventIdForUpdate("e15"))
                .thenReturn(consumedRow(15L, "e15", 1, LocalDateTime.now(), 30L));

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.acknowledge("CONSUMED", "e15", "op-c1", "试图确认 status1", "42"));

        assertEquals(409, rejected.getCode());
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void duplicateAcknowledgeWithSameShapeDoesNotInsertAgain() {
        when(mapper.selectRepairLogByOperationId("op-2"))
                .thenReturn(existingAcknowledge("op-2", "e16", "同一原因", "42", "OUTBOX", 3, 3,
                        null, null));

        VideoStatusFailureAdminService.AcknowledgeResult result =
                service.acknowledge("OUTBOX", "e16", "op-2", "同一原因", "42");

        assertTrue(result.duplicate());
        assertFalse(result.acknowledgedApplied());
        assertEquals(3, result.statusBefore());
        assertEquals(3, result.statusAfter());
        verify(mapper, never()).insertCompletedRepairLog(any());
        verify(mapper, never()).selectFailedOutboxByEventIdForUpdate(anyString());
    }

    @Test
    void sameTargetDuplicateOperationIsDetectedAfterTargetLockForConcurrentIdempotency() {
        LocalDateTime completedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 7);
        when(mapper.selectRepairLogByOperationId("op-after-lock")).thenReturn(null);
        when(mapper.selectFailedOutboxByEventIdForUpdate("e19"))
                .thenReturn(outboxRow(19L, "e19", 10, 0, 0));
        when(mapper.selectRepairLogByOperationIdForUpdate("op-after-lock"))
                .thenReturn(existingAcknowledge("op-after-lock", "e19", "并发同一目标",
                        "42", "OUTBOX", 3, 3, null, null, completedAt));

        VideoStatusFailureAdminService.AcknowledgeResult result = service.acknowledge(
                "OUTBOX", "e19", "op-after-lock", "并发同一目标", "42");

        assertTrue(result.duplicate());
        assertFalse(result.acknowledgedApplied());
        assertEquals(completedAt, result.completedAt(),
                "目标锁后命中的已完成审计必须返回 DB 中持久化的 completed_at");
        verify(mapper).selectRepairLogByOperationIdForUpdate("op-after-lock");
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void sameOperationIdOnDifferentTargetAfterTargetLockIsRejectedAs409() {
        when(mapper.selectRepairLogByOperationId("op-cross-target")).thenReturn(null);
        when(mapper.selectFailedOutboxByEventIdForUpdate("e20"))
                .thenReturn(outboxRow(20L, "e20", 10, 0, 0));
        when(mapper.selectRepairLogByOperationIdForUpdate("op-cross-target"))
                .thenReturn(existingAcknowledge("op-cross-target", "e21", "跨目标冲突",
                        "42", "OUTBOX", 3, 3, null, null));

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service.acknowledge("OUTBOX", "e20", "op-cross-target",
                        "跨目标冲突", "42"));

        assertEquals(409, conflict.getCode(),
                "不同目标复用同一 operationId 仍是明确 409 冲突");
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void sameOperationIdWithDifferentReasonOrSourceIsRejected() {
        when(mapper.selectRepairLogByOperationId("op-3"))
                .thenReturn(existingAcknowledge("op-3", "e17", "原原因", "42", "OUTBOX", 3, 3,
                        null, null));

        BusinessException reasonConflict = assertThrows(BusinessException.class,
                () -> service.acknowledge("OUTBOX", "e17", "op-3", "新原因", "42"));
        assertEquals(409, reasonConflict.getCode());

        BusinessException sourceConflict = assertThrows(BusinessException.class,
                () -> service.acknowledge("CONSUMED", "e17", "op-3", "原原因", "42"));
        assertEquals(409, sourceConflict.getCode());
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void auditMetadataWithDifferentBeforeAndAfterIsRejectedAsNotAcknowledge() {
        when(mapper.selectRepairLogByOperationId("op-4"))
                .thenReturn(existingAcknowledge("op-4", "e18", "原原因", "42", "CONSUMED",
                        null, null, 3, 1));

        BusinessException mismatch = assertThrows(BusinessException.class,
                () -> service.acknowledge("CONSUMED", "e18", "op-4", "原原因", "42"));

        assertEquals(409, mismatch.getCode());
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void acknowledgeIsDeclaredTransactionalWithRollbackForAnyException() throws Exception {
        Method acknowledge = VideoStatusFailureAdminServiceImpl.class.getMethod(
                "acknowledge", String.class, String.class, String.class, String.class, String.class);
        Transactional transactional = acknowledge.getAnnotation(Transactional.class);
        assertTrue(transactional != null, "acknowledge 必须声明事务");
        assertTrue(List.of(transactional.rollbackFor()).contains(Exception.class),
                "acknowledge 必须在任何异常时整体回滚");
    }

    @Test
    void invalidSourcePagingAndEventIdAreRejectedBeforeMapper() {
        BusinessException source = assertThrows(BusinessException.class,
                () -> service.list("DLQ", 0L, 10));
        assertEquals(400, source.getCode());
        BusinessException limit = assertThrows(BusinessException.class,
                () -> service.list("OUTBOX", 0L, 101));
        assertEquals(400, limit.getCode());
        BusinessException after = assertThrows(BusinessException.class,
                () -> service.list("OUTBOX", -1L, 10));
        assertEquals(400, after.getCode());
        verify(mapper, never()).selectFailedOutbox(anyLong(), anyInt());
    }

    @Test
    void missingOutboxFailureListIndexReturns503WithoutScanning() {
        when(mapper.selectIndexColumns("video_status_outbox",
                VideoStatusFailureAdminServiceImpl.OUTBOX_FAILURE_LIST_INDEX))
                .thenReturn(List.of());

        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.list("OUTBOX", 0L, 10));

        assertEquals(503, missing.getCode());
        assertTrue(missing.getMessage().contains(
                VideoStatusFailureAdminServiceImpl.OUTBOX_FAILURE_LIST_INDEX));
        verify(mapper, never()).selectFailedOutbox(anyLong(), anyInt());
    }

    @Test
    void wrongConsumedFailureListIndexKeyOrderReturns503WithoutScanning() {
        when(mapper.selectIndexColumns("video_status_consumed_event",
                VideoStatusFailureAdminServiceImpl.CONSUMED_FAILURE_LIST_INDEX))
                .thenReturn(List.of("id", "process_status"));

        BusinessException wrongOrder = assertThrows(BusinessException.class,
                () -> service.list("CONSUMED", 0L, 10));

        assertEquals(503, wrongOrder.getCode());
        assertTrue(wrongOrder.getMessage().contains("key 顺序"));
        verify(mapper, never()).selectDiagnosableConsumed(anyLong(), anyInt());
    }

    private static OutboxRow outboxRow(long id, String eventId, int retry, int lease, int confirm) {
        OutboxRow row = new OutboxRow();
        row.setId(id);
        row.setEventId(eventId);
        row.setVid(100);
        row.setEventType("PLAY");
        row.setStatus(3);
        row.setRetryCount(retry);
        row.setLeaseRecoveryCount(lease);
        row.setConfirmWritebackCount(confirm);
        row.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        row.setLastErrorPresent(true);
        return row;
    }

    private static ConsumedRow consumedRow(long id, String eventId, int status,
                                           LocalDateTime redisAppliedAt, Long age) {
        ConsumedRow row = new ConsumedRow();
        row.setId(id);
        row.setEventId(eventId);
        row.setVid(100);
        row.setEventType("PLAY");
        row.setProcessStatus(status);
        row.setConsumerRetryCount(status == 3 ? 5 : 0);
        row.setConsumedAt(LocalDateTime.now().minusMinutes(5));
        row.setLastAttemptAt(LocalDateTime.now().minusMinutes(1));
        row.setRedisAppliedAt(redisAppliedAt);
        row.setLastErrorPresent(true);
        row.setConsumedAgeSeconds(age);
        return row;
    }

    private VideoStatusConsumptionRepairLog existingAcknowledge(
            String operationId, String eventId, String reason, String operator, String source,
            Integer statusBefore, Integer statusAfter, Integer processStatusBefore,
            Integer processStatusAfter) {
        return existingAcknowledge(operationId, eventId, reason, operator, source,
                statusBefore, statusAfter, processStatusBefore, processStatusAfter,
                LocalDateTime.now().minusMinutes(1));
    }

    private VideoStatusConsumptionRepairLog completedAudit(
            String operationId, String eventId, String reason, String operator, String source,
            Integer statusBefore, Integer statusAfter, Integer processStatusBefore,
            Integer processStatusAfter, LocalDateTime completedAt) {
        return existingAcknowledge(operationId, eventId, reason, operator, source,
                statusBefore, statusAfter, processStatusBefore, processStatusAfter, completedAt);
    }

    private VideoStatusConsumptionRepairLog existingAcknowledge(
            String operationId, String eventId, String reason, String operator, String source,
            Integer statusBefore, Integer statusAfter, Integer processStatusBefore,
            Integer processStatusAfter, LocalDateTime completedAt) {
        try {
            VideoStatusConsumptionRepairLog log = new VideoStatusConsumptionRepairLog();
            log.setOperationId(operationId);
            log.setEventId(eventId);
            log.setAction("ACKNOWLEDGE_FAILURE");
            log.setOperationStatus(1);
            log.setReason(reason);
            log.setOperator(operator);
            log.setLastError(objectMapper.writeValueAsString(
                    new VideoStatusFailureAdminServiceImpl.AcknowledgeAuditMetadata(
                            "T10_ACKNOWLEDGE_META_V1", source, statusBefore, statusAfter,
                            processStatusBefore, processStatusAfter)));
            log.setCompletedAt(completedAt);
            return log;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
