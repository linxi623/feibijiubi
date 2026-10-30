package com.feibijiubi.backend.videostats.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.entity.VideoStatusConsumptionRepairLog;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.videostats.mapper.VideoStatusFailureAdminMapper;
import com.feibijiubi.backend.videostats.service.admin.VideoStatusStatus3ReplayRow.ConsumedTarget;
import com.feibijiubi.backend.videostats.service.admin.VideoStatusStatus3ReplayRow.OutboxSource;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusEventFingerprintServiceImpl;
import com.feibijiubi.backend.videostats.service.VideoStatusEventAgeGuard;
import com.feibijiubi.backend.infrastructure.redis.RedisKeyUtils;
import com.feibijiubi.backend.videostats.vo.VideoStatusStatus3ReplayVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T10-B1 consumed status3 受控重放的纯单元测试：不连 MySQL / Redis / MQ，不启动 Spring。
 *
 * <p>覆盖完整拒绝矩阵（目标状态、刷库型 status3、安全窗口、Outbox 投递源、payload 合法性、
 * eventId/vid/type/delta 一致性、语义指纹、processed 证据缺失/无 TTL/TTL 不足/读取异常、
 * 重放预算）、CAS 影响行数非 1、审计写入冲突、operationId 幂等形状冲突，以及
 * “拒绝时不写任何状态、预检查与重放判定一致、payload 哨兵不出现在响应/审计”这几条不变量。</p>
 *
 * <p>真实事务回滚由 {@code VideoStatusStatus3ReplayIsolatedTests} 用真实 MySQL 证明，
 * 本类不声称证明事务语义。</p>
 */
class VideoStatusStatus3ReplayServiceTests {

    private static final String SOURCE = VideoStatusFailureAdminService.SOURCE_CONSUMED;
    private static final String OPERATOR = "42";
    private static final String REASON = "受控重放：已确认 processed 幂等证据";
    private static final String PAYLOAD_SENTINEL = "T10B1_PAYLOAD_SENTINEL";
    private static final int VID = 77;

    private VideoStatusFailureAdminMapper mapper;
    private StringRedisTemplate redis;
    private VideoStatusProperties properties;
    private ObjectMapper objectMapper;
    private VideoStatusEventFingerprintServiceImpl fingerprint;
    private VideoStatusStatus3ReplayService service;

    private VideoStatusChangedEvent event;
    private String eventId;

    @BeforeEach
    void setUp() {
        mapper = mock(VideoStatusFailureAdminMapper.class);
        redis = mock(StringRedisTemplate.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        fingerprint = new VideoStatusEventFingerprintServiceImpl();
        properties = new VideoStatusProperties();
        properties.setConsumerRecoveryAutoReplayMaxAgeSeconds(600);
        properties.validate();
        service = new VideoStatusStatus3ReplayService(
                mapper, properties, objectMapper, fingerprint, redis);

        eventId = "t10b1-" + System.nanoTime();
        event = new VideoStatusChangedEvent(eventId, VID, VideoStatusEventType.PLAY, 1L, 1D,
                LocalDateTime.now(), 2, null);
    }

    // ----------------------------------------------------------------- happy path

    @Test
    void provenStatus3IsResetToReceivedAndOutboxBackToPendingInOneAudit() {
        stubTarget(target(3, null, 30L, fingerprint.hash(event)));
        stubOutbox(outbox(2));
        stubAttempts(0);
        stubProcessedTtl(properties.getStatus3ReplayRequiredProcessedTtlSeconds() + 60);
        stubWrites(1, 1, 1);

        VideoStatusStatus3ReplayVO vo = service.replay(SOURCE, eventId, "op-1", REASON, OPERATOR);

        assertTrue(vo.isReplayApplied());
        assertFalse(vo.isDuplicate());
        assertEquals("COMPLETED", vo.getOperationStatus());
        assertEquals(3, vo.getProcessStatusBefore());
        assertEquals(0, vo.getProcessStatusAfter());
        assertEquals("SENT", vo.getOutboxStatusNameBefore());
        assertEquals("PENDING", vo.getOutboxStatusNameAfter());
        assertEquals(1, vo.getReplayAttemptNo());
        assertEquals("OUTBOX_PENDING_RELAY", vo.getDeliveryMode(),
                "投递必须交给既有 Outbox relay，而不是由请求线程发送");
        for (java.lang.reflect.Field field : VideoStatusStatus3ReplayVO.class.getDeclaredFields()) {
            assertFalse(field.getName().equalsIgnoreCase("payload"), field.getName());
            assertFalse(field.getName().equalsIgnoreCase("leaseToken"), field.getName());
            assertFalse(field.getName().equalsIgnoreCase("lastError"), field.getName());
        }

        verify(mapper).resetConsumedRepairToReceivedForReplay(eventId, fingerprint.hash(event));
        verify(mapper).resetOutboxSentToPendingForReplay(eventId);
        verify(mapper).insertCompletedRepairLog(any());
        // 预算查询必须有界：cap = maxAttempts + 1。
        verify(mapper).countReplayAttempts(
                eventId, VideoStatusStatus3ReplayService.ACTION_REPLAY_TO_RECEIVED,
                properties.getStatus3ReplayMaxAttempts() + 1);
    }

    @Test
    void auditMetadataRecordsBeforeAfterAndNeverLeaksPayloadOrEventIdentity() {
        // traceId 参与语义指纹，但仍属于 payload 内容：它不得出现在审计元数据或响应里。
        VideoStatusChangedEvent sentinelEvent = new VideoStatusChangedEvent(eventId, VID,
                VideoStatusEventType.PLAY, 1L, 1D, LocalDateTime.now(), 2, PAYLOAD_SENTINEL);
        String sentinelHash = fingerprint.hash(sentinelEvent);
        stubTarget(target(3, null, 30L, sentinelHash));
        stubOutbox(outboxFor(sentinelEvent, 2));
        stubAttempts(0);
        stubProcessedTtl(properties.getStatus3ReplayRequiredProcessedTtlSeconds());
        stubWrites(1, 1, 1);

        VideoStatusStatus3ReplayVO vo =
                service.replay(SOURCE, eventId, "op-meta", REASON, OPERATOR);

        ArgumentCaptor<VideoStatusConsumptionRepairLog> captor =
                ArgumentCaptor.forClass(VideoStatusConsumptionRepairLog.class);
        verify(mapper).insertCompletedRepairLog(captor.capture());
        VideoStatusConsumptionRepairLog log = captor.getValue();
        assertEquals(VideoStatusStatus3ReplayService.ACTION_REPLAY_TO_RECEIVED, log.getAction());
        assertEquals(eventId, log.getEventId());
        assertEquals(OPERATOR, log.getOperator());
        assertEquals(REASON, log.getReason());
        assertTrue(log.getLastError().contains(
                VideoStatusStatus3ReplayService.AUDIT_METADATA_SCHEMA));
        assertFalse(log.getLastError().contains(sentinelHash),
                "审计元数据不记录语义指纹原文");
        assertFalse(log.getLastError().contains(PAYLOAD_SENTINEL));
        assertTrue(log.getLastError().length() <= 1000);
        try {
            String serializedVo = objectMapper.writeValueAsString(vo);
            assertFalse(serializedVo.contains(PAYLOAD_SENTINEL), serializedVo);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void missingTargetIsNotFound() {
        when(mapper.selectStatus3ReplayTargetForUpdate(eventId)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-2", REASON, OPERATOR));

        assertEquals(404, error.getCode());
        verifyNoInteractions(redis);
        verify(mapper, never()).resetConsumedRepairToReceivedForReplay(anyString(), anyString());
    }

    @Test
    void nonStatus3TargetsAreRejectedWithoutTouchingRedisOrState() {
        for (int processStatus : new int[]{0, 1, 2}) {
            setUp();
            stubTarget(target(processStatus, null, 30L, fingerprint.hash(event)));
            stubOutbox(outbox(2));

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.replay(SOURCE, eventId, "op-3", REASON, OPERATOR));

            assertEquals(409, error.getCode(), "process_status=" + processStatus);
            assertTrue(error.getMessage().contains(
                    VideoStatusStatus3ReplayService.REFUSAL_TARGET_NOT_REPAIR_REQUIRED));
            verifyNoInteractions(redis);
            verify(mapper, never()).resetConsumedRepairToReceivedForReplay(anyString(), anyString());
            verify(mapper, never()).insertCompletedRepairLog(any());
        }
    }

    @Test
    void flushOriginStatus3IsRejected() {
        stubTarget(target(3, LocalDateTime.now(), 30L, fingerprint.hash(event)));
        stubOutbox(outbox(2));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-4", REASON, OPERATOR));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains(
                VideoStatusStatus3ReplayService.REFUSAL_FLUSH_ORIGIN_NOT_REPLAYABLE));
        verifyNoInteractions(redis);
        verify(mapper, never()).resetOutboxSentToPendingForReplay(anyString());
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void consumedAgeOutsideSafeWindowIsRejected() {
        long maxAge = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        for (Long age : new Long[]{maxAge, maxAge + 1, -1L, null}) {
            setUp();
            stubTarget(target(3, null, age, fingerprint.hash(event)));
            stubOutbox(outbox(2));

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.replay(SOURCE, eventId, "op-5", REASON, OPERATOR));

            assertEquals(409, error.getCode(), "age=" + age);
            assertTrue(error.getMessage().contains(
                    VideoStatusStatus3ReplayService.REFUSAL_OVER_SAFE_WINDOW));
            verify(mapper, never()).resetConsumedRepairToReceivedForReplay(anyString(), anyString());
        }
    }

    @Test
    void occurredAtOutsideSafeWindowIsRejectedEvenWhenDbAgeLooksFine() {
        VideoStatusChangedEvent stale = new VideoStatusChangedEvent(eventId, VID,
                VideoStatusEventType.PLAY, 1L, 1D,
                LocalDateTime.now().minusSeconds(
                        properties.getConsumerRecoveryAutoReplayMaxAgeSeconds() + 1),
                2, null);
        stubTarget(target(3, null, 30L, fingerprint.hash(stale)));
        stubOutbox(outboxFor(stale, 2));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-6", REASON, OPERATOR));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains(
                VideoStatusStatus3ReplayService.REFUSAL_OVER_SAFE_WINDOW));
    }

    @Test
    void futureOccurredAtBeyondToleranceIsRejected() {
        VideoStatusChangedEvent future = new VideoStatusChangedEvent(eventId, VID,
                VideoStatusEventType.PLAY, 1L, 1D,
                LocalDateTime.now().plusSeconds(600), 2, null);
        stubTarget(target(3, null, 30L, fingerprint.hash(future)));
        stubOutbox(outboxFor(future, 2));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-7", REASON, OPERATOR));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains(
                VideoStatusStatus3ReplayService.REFUSAL_OVER_SAFE_WINDOW));
    }

    @Test
    void outboxSourceMustExistAndBeSent() {
        stubTarget(target(3, null, 30L, fingerprint.hash(event)));

        BusinessException missing = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-8", REASON, OPERATOR));
        assertEquals(409, missing.getCode());
        assertTrue(missing.getMessage().contains(
                VideoStatusStatus3ReplayService.REFUSAL_OUTBOX_LEDGER_MISSING));

        for (int status : new int[]{0, 1, 3}) {
            setUp();
            stubTarget(target(3, null, 30L, fingerprint.hash(event)));
            stubOutbox(outbox(status));

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.replay(SOURCE, eventId, "op-9", REASON, OPERATOR));

            assertEquals(409, error.getCode(), "outbox status=" + status);
            assertTrue(error.getMessage().contains(
                    VideoStatusStatus3ReplayService.REFUSAL_OUTBOX_NOT_SENT));
        }
        verifyNoInteractions(redis);
    }

    @Test
    void unparseableOrInvalidPayloadIsRejectedAsUnprocessable() {
        stubTarget(target(3, null, 30L, fingerprint.hash(event)));
        OutboxSource broken = outbox(2);
        broken.setPayload("{\"not\":\"an event\"}");
        stubOutbox(broken);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-10", REASON, OPERATOR));

        assertEquals(422, error.getCode(), "payload 无法安全解释时不得返回 200/409");
        assertTrue(error.getMessage().contains(
                VideoStatusStatus3ReplayService.REFUSAL_PAYLOAD_INVALID));
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void eventSemanticMismatchIsRejected() {
        VideoStatusChangedEvent otherVid = new VideoStatusChangedEvent(eventId, VID + 1,
                VideoStatusEventType.PLAY, 1L, 1D, LocalDateTime.now(), 2, null);
        stubTarget(target(3, null, 30L, fingerprint.hash(otherVid)));
        stubOutbox(outboxFor(otherVid, 2));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-11", REASON, OPERATOR));

        assertEquals(422, error.getCode());
        assertTrue(error.getMessage().contains(
                VideoStatusStatus3ReplayService.REFUSAL_EVENT_MISMATCH));

        setUp();
        ConsumedTarget wrongDelta = target(3, null, 30L, fingerprint.hash(event));
        wrongDelta.setDelta(99L);
        stubTarget(wrongDelta);
        stubOutbox(outbox(2));

        BusinessException deltaError = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-12", REASON, OPERATOR));

        assertEquals(422, deltaError.getCode());
        assertTrue(deltaError.getMessage().contains(
                VideoStatusStatus3ReplayService.REFUSAL_EVENT_MISMATCH));
    }

    @Test
    void fingerprintMismatchIsRejectedSoConsumerCannotRejectItAsNonRetryable() {
        stubTarget(target(3, null, 30L, "0".repeat(64)));
        stubOutbox(outbox(2));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-13", REASON, OPERATOR));

        assertEquals(422, error.getCode());
        assertTrue(error.getMessage().contains(
                VideoStatusStatus3ReplayService.REFUSAL_FINGERPRINT_MISMATCH));
        verifyNoInteractions(redis);
    }

    @Test
    void processedEvidenceMatrixIsRejectedAndNeverTreatedAsNotApplied() {
        record Case(Long ttl, String expected) {
        }
        long required = properties.getStatus3ReplayRequiredProcessedTtlSeconds();        java.util.List<Case> cases = java.util.List.of(
                new Case(null, VideoStatusStatus3ReplayService.REFUSAL_PROCESSED_TTL_UNKNOWN),
                new Case(-1L, VideoStatusStatus3ReplayService.REFUSAL_PROCESSED_TTL_UNKNOWN),
                new Case(-2L, VideoStatusStatus3ReplayService.REFUSAL_PROCESSED_EVIDENCE_MISSING),
                new Case(required - 1,
                        VideoStatusStatus3ReplayService.REFUSAL_PROCESSED_TTL_INSUFFICIENT));
        for (Case testCase : cases) {
            setUp();
            stubTarget(target(3, null, 30L, fingerprint.hash(event)));
            stubOutbox(outbox(2));
            stubProcessedTtl(testCase.ttl());

            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.replay(SOURCE, eventId, "op-14", REASON, OPERATOR));

            assertEquals(409, error.getCode(), "ttl=" + testCase.ttl());
            assertTrue(error.getMessage().contains(testCase.expected()), error.getMessage());
            verify(redis).getExpire(RedisKeyUtils.processedKey(eventId), TimeUnit.SECONDS);
            verify(mapper, never()).resetConsumedRepairToReceivedForReplay(anyString(), anyString());
            verify(mapper, never()).insertCompletedRepairLog(any());
        }
    }

    @Test
    void redisEvidenceReadFailureIsRejectedAndNeverFailOpen() {
        stubTarget(target(3, null, 30L, fingerprint.hash(event)));
        stubOutbox(outbox(2));
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS)))
                .thenThrow(new IllegalStateException("redis down"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-15", REASON, OPERATOR));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains(
                VideoStatusStatus3ReplayService.REFUSAL_EVIDENCE_CHECK_FAILED));
        verify(mapper, never()).resetConsumedRepairToReceivedForReplay(anyString(), anyString());
    }

    @Test
    void replayBudgetIsBoundedAndExhaustedRefusesInsteadOfLooping() {
        stubTarget(target(3, null, 30L, fingerprint.hash(event)));
        stubOutbox(outbox(2));
        stubAttempts(properties.getStatus3ReplayMaxAttempts());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-16", REASON, OPERATOR));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains(
                VideoStatusStatus3ReplayService.REFUSAL_REPLAY_BUDGET_EXHAUSTED));
        verifyNoInteractions(redis);
        verify(mapper, never()).resetConsumedRepairToReceivedForReplay(anyString(), anyString());
    }

    @Test
    void occurredAtBeyondFixedThirtyDayBoundIsRefusedByPrecheckAndReplay() {
        useWidenedWindow();
        VideoStatusChangedEvent stale = new VideoStatusChangedEvent(eventId, VID,
                VideoStatusEventType.PLAY, 1L, 1D, LocalDateTime.now().minusDays(31), 2, null);
        stubTarget(target(3, null, 30L, fingerprint.hash(stale)));
        stubOutbox(outboxFor(stale, 2));
        // 预算与 processed 证据故意不打 stub：拒绝必须发生在它们之前（下面用
        // verifyNoInteractions(redis) 与零写断言证明）。窗口内可重放由下一个用例覆盖。

        VideoStatusFailureAdminService.ReplayPrecheck precheck =
                service.precheckStatus3Replay(eventId);

        assertEquals(VideoStatusStatus3ReplayService.DECISION_REFUSED, precheck.decision());
        assertFalse(precheck.eligible());
        assertEquals(VideoStatusEventAgeGuard.REFUSAL_CODE, precheck.refusalCode());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-t14s2", REASON, OPERATOR));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains(precheck.refusalCode()),
                "读写两侧必须给出同一个可诊断原因码");
        verifyNoInteractions(redis);
        verify(mapper, never()).resetConsumedRepairToReceivedForReplay(anyString(), anyString());
        verify(mapper, never()).resetOutboxSentToPendingForReplay(anyString());
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void occurredAtInsideFixedBoundStillReplaysUnderWidenedWindow() {
        useWidenedWindow();
        VideoStatusChangedEvent inside = new VideoStatusChangedEvent(eventId, VID,
                VideoStatusEventType.PLAY, 1L, 1D, LocalDateTime.now().minusDays(29), 2, null);
        stubTarget(target(3, null, 30L, fingerprint.hash(inside)));
        stubOutbox(outboxFor(inside, 2));
        stubAttempts(0);
        stubProcessedTtl(properties.getStatus3ReplayRequiredProcessedTtlSeconds());
        stubWrites(1, 1, 1);

        VideoStatusStatus3ReplayVO vo =
                service.replay(SOURCE, eventId, "op-t14s2-inside", REASON, OPERATOR);

        assertTrue(vo.isReplayApplied());
        verify(mapper).resetConsumedRepairToReceivedForReplay(eventId, fingerprint.hash(inside));
    }

    // --------------------------------------------------------- state-change guards

    @Test
    void consumedCasWithoutExactlyOneRowRollsBackBeforeTouchingOutbox() {
        stubTarget(target(3, null, 30L, fingerprint.hash(event)));
        stubOutbox(outbox(2));
        stubProcessedTtl(properties.getStatus3ReplayRequiredProcessedTtlSeconds());
        when(mapper.resetConsumedRepairToReceivedForReplay(anyString(), anyString())).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-17", REASON, OPERATOR));

        assertEquals(409, error.getCode());
        verify(mapper, never()).resetOutboxSentToPendingForReplay(anyString());
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void outboxCasWithoutExactlyOneRowAbortsTheWholeReplay() {
        stubTarget(target(3, null, 30L, fingerprint.hash(event)));
        stubOutbox(outbox(2));
        stubProcessedTtl(properties.getStatus3ReplayRequiredProcessedTtlSeconds());
        when(mapper.resetConsumedRepairToReceivedForReplay(anyString(), anyString())).thenReturn(1);
        when(mapper.resetOutboxSentToPendingForReplay(anyString())).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-18", REASON, OPERATOR));

        assertEquals(409, error.getCode());
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void auditInsertionWithoutExactlyOneRowIsAFailureNotASuccess() {
        stubTarget(target(3, null, 30L, fingerprint.hash(event)));
        stubOutbox(outbox(2));
        stubProcessedTtl(properties.getStatus3ReplayRequiredProcessedTtlSeconds());
        stubWrites(1, 1, 0);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-19", REASON, OPERATOR));

        assertEquals(500, error.getCode());
    }

    @Test
    void duplicateKeyOnAuditIsTranslatedToConflict() {
        stubTarget(target(3, null, 30L, fingerprint.hash(event)));
        stubOutbox(outbox(2));
        stubProcessedTtl(properties.getStatus3ReplayRequiredProcessedTtlSeconds());
        when(mapper.resetConsumedRepairToReceivedForReplay(anyString(), anyString())).thenReturn(1);
        when(mapper.resetOutboxSentToPendingForReplay(anyString())).thenReturn(1);
        when(mapper.insertCompletedRepairLog(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-20", REASON, OPERATOR));

        assertEquals(409, error.getCode());
    }

    // -------------------------------------------------------------- idempotency

    @Test
    void completedOperationIdReturnsPersistedDuplicateWithoutReapplying() {
        stubTarget(target(0, null, 30L, fingerprint.hash(event)));
        VideoStatusConsumptionRepairLog existing = completedAudit("op-dup", REASON, OPERATOR);
        when(mapper.selectRepairLogByOperationId("op-dup")).thenReturn(existing);

        VideoStatusStatus3ReplayVO vo = service.replay(SOURCE, eventId, "op-dup", REASON, OPERATOR);

        assertTrue(vo.isDuplicate());
        assertFalse(vo.isReplayApplied());
        assertEquals(3, vo.getProcessStatusBefore());
        assertEquals(0, vo.getProcessStatusAfter());
        assertEquals("SENT", vo.getOutboxStatusNameBefore());
        assertEquals("PENDING", vo.getOutboxStatusNameAfter());
        assertEquals(existing.getCompletedAt(), vo.getCompletedAt());
        verifyNoInteractions(redis);
        verify(mapper, never()).resetConsumedRepairToReceivedForReplay(anyString(), anyString());
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void operationIdShapeMismatchIsRejected() {
        stubTarget(target(0, null, 30L, fingerprint.hash(event)));

        VideoStatusConsumptionRepairLog otherAction = completedAudit("op-shape", REASON, OPERATOR);
        otherAction.setAction(VideoStatusFailureAdminService.ACTION_ACKNOWLEDGE_FAILURE);
        when(mapper.selectRepairLogByOperationId("op-shape")).thenReturn(otherAction);

        BusinessException actionError = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-shape", REASON, OPERATOR));
        assertEquals(409, actionError.getCode());

        VideoStatusConsumptionRepairLog otherReason = completedAudit("op-reason", REASON, OPERATOR);
        when(mapper.selectRepairLogByOperationId("op-reason")).thenReturn(otherReason);

        BusinessException reasonError = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-reason", "换一个原因", OPERATOR));
        assertEquals(409, reasonError.getCode());

        VideoStatusConsumptionRepairLog noMetadata =
                completedAudit("op-nometa", REASON, OPERATOR);
        noMetadata.setLastError("not-json");
        when(mapper.selectRepairLogByOperationId("op-nometa")).thenReturn(noMetadata);

        BusinessException metadataError = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-nometa", REASON, OPERATOR));
        assertEquals(409, metadataError.getCode(),
                "无法证明是同一 status3 重放的 operationId 不能被当作 duplicate 成功");
    }

    // ---------------------------------------------------------------- validation

    @Test
    void onlyConsumedSourceAndValidAuditFieldsAreAccepted() {
        BusinessException outboxSource = assertThrows(BusinessException.class,
                () -> service.replay("OUTBOX", eventId, "op-21", REASON, OPERATOR));
        assertEquals(400, outboxSource.getCode());

        BusinessException blankSource = assertThrows(BusinessException.class,
                () -> service.replay(" ", eventId, "op-21", REASON, OPERATOR));
        assertEquals(400, blankSource.getCode());

        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "o".repeat(65), REASON, OPERATOR))
                .getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-22", "r".repeat(1001), OPERATOR))
                .getCode());
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-23", REASON, null))
                .getCode());
        verifyNoInteractions(mapper, redis);
    }

    // ------------------------------------------------------------------- precheck

    @Test
    void precheckUsesTheSameEvaluationAsReplayAndNeverWrites() {
        stubTarget(target(3, null, 30L, fingerprint.hash(event)));
        stubOutbox(outbox(2));
        stubAttempts(0);
        stubProcessedTtl(properties.getStatus3ReplayRequiredProcessedTtlSeconds());

        VideoStatusFailureAdminService.ReplayPrecheck eligible =
                service.precheckStatus3Replay(eventId);

        assertEquals(VideoStatusStatus3ReplayService.DECISION_PROVEN_REPLAY_ELIGIBLE,
                eligible.decision());
        assertTrue(eligible.eligible());
        assertFalse(eligible.redisAppliedEvidencePresent(),
                "processed 证据不能冒充 redis_applied_at 刷库证据");
        assertNull(eligible.refusalCode());
        assertEquals(0, eligible.replayAttempts());
        assertEquals(properties.getStatus3ReplayMaxAttempts(), eligible.maxReplayAttempts());
        verify(mapper, never()).resetConsumedRepairToReceivedForReplay(anyString(), anyString());
        verify(mapper, never()).resetOutboxSentToPendingForReplay(anyString());
        verify(mapper, never()).insertCompletedRepairLog(any());
    }

    @Test
    void precheckRefusalCodeMatchesTheReplayRejectionCode() {
        stubTarget(target(3, null, 30L, fingerprint.hash(event)));
        stubOutbox(outbox(2));
        stubAttempts(0);
        stubProcessedTtl(-2L);

        VideoStatusFailureAdminService.ReplayPrecheck precheck =
                service.precheckStatus3Replay(eventId);
        BusinessException replayError = assertThrows(BusinessException.class,
                () -> service.replay(SOURCE, eventId, "op-24", REASON, OPERATOR));

        assertEquals(VideoStatusStatus3ReplayService.DECISION_REFUSED, precheck.decision());
        assertFalse(precheck.eligible());
        assertEquals(VideoStatusStatus3ReplayService.REFUSAL_PROCESSED_EVIDENCE_MISSING,
                precheck.refusalCode());
        assertEquals(409, replayError.getCode());
        assertTrue(replayError.getMessage().contains(precheck.refusalCode()),
                "读写两侧必须给出同一个可诊断原因码");
    }

    @Test
    void precheckIsNotProducedForNonStatus3Rows() {
        ConsumedTarget status0 = target(0, null, 30L, fingerprint.hash(event));
        when(mapper.selectStatus3ReplayTarget(eventId)).thenReturn(status0);

        assertNull(service.precheckStatus3Replay(eventId));
        verifyNoInteractions(redis);
    }

    @Test
    void status3ReplayAttemptLimitIsValidatedAtStartup() {
        VideoStatusProperties invalid = new VideoStatusProperties();
        invalid.setConsumerRecoveryAutoReplayMaxAgeSeconds(600);
        invalid.setStatus3ReplayMaxAttempts(0);
        assertThrows(IllegalStateException.class, invalid::validate);
    }

    // -------------------------------------------------------------------- stubs

    /**
     * 合法但把可配置重放窗口调到 30 天以上的配置：启动校验只要求窗口小于
     * {@code redisEventTtlDays * 86400}，所以 60 天 TTL + 45 天窗口可以通过 {@code validate()}。
     */
    private void useWidenedWindow() {
        properties = new VideoStatusProperties();
        properties.setRedisEventTtlDays(60);
        properties.setConsumerRecoveryAutoReplayMaxAgeSeconds(
                (int) Duration.ofDays(45).toSeconds());
        properties.validate();
        service = new VideoStatusStatus3ReplayService(
                mapper, properties, objectMapper, fingerprint, redis);
    }

    private void stubTarget(ConsumedTarget target) {
        when(mapper.selectStatus3ReplayTargetForUpdate(eventId)).thenReturn(target);
        when(mapper.selectStatus3ReplayTarget(eventId)).thenReturn(target);
    }

    private void stubOutbox(OutboxSource outbox) {
        when(mapper.selectOutboxReplaySourceForUpdate(eventId)).thenReturn(outbox);
        when(mapper.selectOutboxReplaySource(eventId)).thenReturn(outbox);
    }

    private void stubAttempts(int attempts) {
        when(mapper.countReplayAttempts(eq(eventId),
                eq(VideoStatusStatus3ReplayService.ACTION_REPLAY_TO_RECEIVED), anyInt()))
                .thenReturn(attempts);
    }

    private void stubProcessedTtl(Long ttl) {
        when(redis.getExpire(RedisKeyUtils.processedKey(eventId), TimeUnit.SECONDS))
                .thenReturn(ttl);
    }

    private void stubWrites(int consumedRows, int outboxRows, int auditRows) {
        when(mapper.resetConsumedRepairToReceivedForReplay(anyString(), anyString()))
                .thenReturn(consumedRows);
        when(mapper.resetOutboxSentToPendingForReplay(anyString())).thenReturn(outboxRows);
        when(mapper.insertCompletedRepairLog(any())).thenReturn(auditRows);
        VideoStatusConsumptionRepairLog persisted = completedAudit("op", REASON, OPERATOR);
        when(mapper.selectRepairLogByOperationId(anyString()))
                .thenReturn(null, persisted);
    }

    private ConsumedTarget target(int processStatus, LocalDateTime redisAppliedAt, Long ageSeconds,
                                  String payloadHash) {
        ConsumedTarget target = new ConsumedTarget();
        target.setId(1L);
        target.setEventId(eventId);
        target.setVid(VID);
        target.setEventType(VideoStatusEventType.PLAY.name());
        target.setDelta(event.delta());
        target.setPayloadHash(payloadHash);
        target.setProcessStatus(processStatus);
        target.setConsumerRetryCount(3);
        target.setConsumedAt(LocalDateTime.now().minusSeconds(ageSeconds == null ? 0 : ageSeconds));
        target.setRedisAppliedAt(redisAppliedAt);
        target.setLastErrorPresent(Boolean.TRUE);
        target.setConsumedAgeSeconds(ageSeconds);
        return target;
    }

    private OutboxSource outbox(int status) {
        return outboxFor(event, status);
    }

    private OutboxSource outboxFor(VideoStatusChangedEvent source, int status) {
        OutboxSource outbox = new OutboxSource();
        outbox.setId(2L);
        outbox.setEventId(source.eventId());
        outbox.setVid(source.vid());
        outbox.setEventType(source.type().name());
        try {
            outbox.setPayload(objectMapper.writeValueAsString(source));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        outbox.setStatus(status);
        outbox.setSentAt(LocalDateTime.now().minusSeconds(60));
        return outbox;
    }

    private VideoStatusConsumptionRepairLog completedAudit(String operationId, String reason,
                                                           String operator) {
        VideoStatusConsumptionRepairLog log = new VideoStatusConsumptionRepairLog();
        log.setId(9L);
        log.setOperationId(operationId);
        log.setEventId(eventId);
        log.setAction(VideoStatusStatus3ReplayService.ACTION_REPLAY_TO_RECEIVED);
        log.setOperationStatus(1);
        log.setReason(reason);
        log.setOperator(operator);
        log.setLastError("{\"schema\":\""
                + VideoStatusStatus3ReplayService.AUDIT_METADATA_SCHEMA
                + "\",\"processStatusBefore\":3,\"processStatusAfter\":0,"
                + "\"consumerRetryCountBefore\":3,\"consumerRetryCountAfter\":3,"
                + "\"consumedAgeSeconds\":30,\"replayAttemptNo\":1,"
                + "\"outboxStatusBefore\":2,\"outboxStatusAfter\":0,"
                + "\"processedTtlSeconds\":600}");
        log.setCompletedAt(LocalDateTime.now().minusMinutes(1));
        return log;
    }
}
