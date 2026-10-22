package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusOutbox;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.mq.VideoStatusEventPublisher;
import com.feibijiubi.backend.mq.VideoStatusOutboxClaimService;
import com.feibijiubi.backend.mq.VideoStatusOutboxRelay;
import com.feibijiubi.backend.mq.VideoStatusPublishException;
import com.feibijiubi.backend.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T07：Outbox 发布 / confirm / 回写 / 租约的分类与隔离。
 *
 * <p>固定的契约（详见 docs/tutorial/video-status-outbox-reliability.md）：</p>
 * <ul>
 *   <li>JSON/事件非法 → markInvalidFailed（不增加 retry_count、不重试）；</li>
 *   <li>发布失败 / confirm 不确定 → markPending 或（达到上限）markFailed，retry_count+1；</li>
 *   <li>confirm 成功但 markSent 抛异常 → markConfirmedPending（不加 retry_count），
 *       绝不判 FAILED；二次回写失败保留 SENDING 等租约；</li>
 *   <li>CAS 0 行只诊断、不再改状态；单条异常不中断本轮剩余事件；</li>
 *   <li>指标 PUBLISH SUCCESS 表示 confirm 成功，回写失败也仍是 SUCCESS。</li>
 * </ul>
 */
class VideoStatusOutboxRelayTests {

    private static final String EVENT_ID = "evt-t07";
    private static final String LEASE = "lease-t07";
    private static final String EVENT_ID_2 = "evt-t07-next";

    // ---------------------------------------------------------------- helpers

    private VideoStatusOutbox outbox(String eventId, String leaseToken, int retryCount) {
        return outbox(eventId, leaseToken, retryCount, 0);
    }

    private VideoStatusOutbox outbox(String eventId,
                                     String leaseToken,
                                     int retryCount,
                                     int confirmWritebackCount) {
        VideoStatusOutbox outbox = new VideoStatusOutbox();
        outbox.setId(1L);
        outbox.setEventId(eventId);
        outbox.setAggregateId(7);
        outbox.setEventType("PLAY");
        // payload 用 eventId 占位，配合 jsonUtils answer 生成语义一致的 event
        outbox.setPayload(eventId);
        outbox.setStatus(1);
        outbox.setRetryCount(retryCount);
        outbox.setConfirmWritebackCount(confirmWritebackCount);
        outbox.setLeaseToken(leaseToken);
        outbox.setSendingAt(LocalDateTime.now());
        return outbox;
    }

    private VideoStatusOutbox outbox(int retryCount) {
        return outbox(EVENT_ID, LEASE, retryCount);
    }

    private VideoStatusChangedEvent event(String eventId) {
        return new VideoStatusChangedEvent(eventId, 7, VideoStatusEventType.PLAY,
                1L, 1D, LocalDateTime.now(), 2, null);
    }

    private VideoStatusProperties properties() {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        return properties;
    }

    /** payload == eventId，让同一次 relay 里的多条事件各自语义一致。 */
    private void stubConsistentJson(JsonUtils jsonUtils) {
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenAnswer(invocation -> event(invocation.getArgument(0)));
    }

    private VideoStatusOutboxRelay relay(
            VideoStatusOutboxClaimService claimService,
            VideoStatusOutboxMapper outboxMapper,
            VideoStatusEventPublisher publisher,
            JsonUtils jsonUtils
    ) {
        return new VideoStatusOutboxRelay(
                claimService, outboxMapper, publisher, properties(), jsonUtils);
    }

    private VideoStatusOutboxRelay relay(
            VideoStatusOutboxClaimService claimService,
            VideoStatusOutboxMapper outboxMapper,
            VideoStatusEventPublisher publisher,
            JsonUtils jsonUtils,
            VideoStatusTelemetry telemetry
    ) {
        VideoStatusOutboxRelay relay = relay(
                claimService, outboxMapper, publisher, jsonUtils);
        ReflectionTestUtils.setField(relay, "telemetry", telemetry);
        return relay;
    }

    // ------------------------------------------- confirm 成功但回写失败（T01 窗口）

    @Test
    void confirmedButMarkSentThrowsFallsBackToConfirmedPendingWithoutPublishFailure() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        stubConsistentJson(jsonUtils);
        when(outboxMapper.markSent(eq(EVENT_ID), eq(LEASE), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> relay(claimService, outboxMapper, publisher, jsonUtils).relay());

        verify(publisher).publish(any(VideoStatusChangedEvent.class), eq(LEASE));
        // 已 confirm → 只能退回 PENDING 等重投，绝不按发布失败处理
        verify(outboxMapper).markConfirmedPending(
                eq(EVENT_ID),
                eq(LEASE),
                any(LocalDateTime.class),
                contains("CONFIRM_WRITEBACK_FAILED")
        );
        verify(outboxMapper, never()).markPending(anyString(), anyString(), any(), any());
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
    }

    @Test
    void confirmedButMarkSentReturnsZeroRowsOnlyDiagnosesAndKeepsSending() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        stubConsistentJson(jsonUtils);
        // 0 行 = 租约已过期/被新 owner 接管：不再改状态
        when(outboxMapper.markSent(eq(EVENT_ID), eq(LEASE), any())).thenReturn(0);

        relay(claimService, outboxMapper, publisher, jsonUtils).relay();

        verify(publisher).publish(any(VideoStatusChangedEvent.class), eq(LEASE));
        verify(outboxMapper, never()).markConfirmedPending(anyString(), anyString(), any(), any());
        verify(outboxMapper, never()).markPending(anyString(), anyString(), any(), any());
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
        // 仍依赖租约超时恢复兜底
        verify(outboxMapper).recoverExpiredSending(any(LocalDateTime.class));
    }

    /**
     * 真实缺陷（T07）：confirm 成功后 markSent 持续抛异常、markConfirmedPending 每次成功，
     * retry_count 与 lease_recovery_count 都不增加，旧实现会每秒永久重投。
     * 修复后使用独立的 confirm_writeback_count 预算：前 9 次回 PENDING，第 10 次 FAILED 终态。
     */
    @Test
    void repeatedConfirmedWritebackFailureIsBoundedByItsOwnBudgetAndStopsAtTen() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        java.util.concurrent.atomic.AtomicInteger round =
                new java.util.concurrent.atomic.AtomicInteger();
        when(claimService.claimBatch(1)).thenAnswer(invocation -> {
            int current = round.getAndIncrement();
            if (current % 2 == 0 && current / 2 < 10) {
                return List.of(outbox(EVENT_ID, LEASE, 0, current / 2));
            }
            return List.of();
        });
        stubConsistentJson(jsonUtils);
        when(outboxMapper.markSent(eq(EVENT_ID), eq(LEASE), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        when(outboxMapper.markConfirmedPending(
                eq(EVENT_ID), eq(LEASE), any(LocalDateTime.class), anyString()))
                .thenReturn(1);
        when(outboxMapper.markConfirmWritebackExhausted(
                eq(EVENT_ID), eq(LEASE), anyString())).thenReturn(1);

        VideoStatusOutboxRelay relay = relay(
                claimService, outboxMapper, publisher, jsonUtils);
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertDoesNotThrow(relay::relay, "round " + attempt + " must not throw");
        }

        // 前 9 次回 PENDING（每次都算一次独立回写失败），第 10 次落 FAILED 终态后不再重投。
        verify(outboxMapper, times(9)).markConfirmedPending(
                eq(EVENT_ID), eq(LEASE), any(LocalDateTime.class),
                contains("CONFIRM_WRITEBACK_FAILED"));
        verify(outboxMapper, times(1)).markConfirmWritebackExhausted(
                eq(EVENT_ID), eq(LEASE), contains("CONFIRM_WRITEBACK_FAILED"));
        verify(publisher, times(10)).publish(any(VideoStatusChangedEvent.class), eq(LEASE));
        verify(outboxMapper, never()).markPending(anyString(), anyString(), any(), any());
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
    }

    @Test
    void confirmedWritebackFailureThenConfirmedPendingFailureKeepsSendingForLeaseRecovery() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        stubConsistentJson(jsonUtils);
        when(outboxMapper.markSent(eq(EVENT_ID), eq(LEASE), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        when(outboxMapper.markConfirmedPending(
                eq(EVENT_ID), eq(LEASE), any(LocalDateTime.class), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db still down"));

        assertDoesNotThrow(() -> relay(claimService, outboxMapper, publisher, jsonUtils).relay());

        // 二次回写失败：不判 FAILED、不改 PENDING，保留 SENDING 等租约恢复
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
        verify(outboxMapper, never()).markPending(anyString(), anyString(), any(), any());
        verify(outboxMapper).recoverExpiredSending(any(LocalDateTime.class));
    }

    // -------------------------------------------------- 发布失败 / confirm 不确定

    @Test
    void publishFailureUsesRetryCountAndSafeReasonOnly() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        stubConsistentJson(jsonUtils);
        // 异常 message 里故意带 payload/凭据片段，验证 last_error 不会泄漏
        doThrow(new VideoStatusPublishException(
                VideoStatusPublishException.Reason.SEND_FAILED,
                new IllegalStateException("{\"password\":\"p@ss\",\"vid\":7}")))
                .when(publisher).publish(any(VideoStatusChangedEvent.class), anyString());

        relay(claimService, outboxMapper, publisher, jsonUtils).relay();

        ArgumentCaptor<String> lastError = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> nextRetryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(outboxMapper).markPending(
                eq(EVENT_ID), eq(LEASE), nextRetryAt.capture(), lastError.capture());
        assertTrue(lastError.getValue().contains("PUBLISH_SEND_FAILED"));
        assertFalse(lastError.getValue().contains("p@ss"));
        assertFalse(lastError.getValue().contains("password"));
        assertTrue(nextRetryAt.getValue().isAfter(LocalDateTime.now()));
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
    }

    @Test
    void publishConfirmTimeoutIsRetriedNotMarkedFailed() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        stubConsistentJson(jsonUtils);
        doThrow(new VideoStatusPublishException(
                VideoStatusPublishException.Reason.CONFIRM_TIMEOUT, null))
                .when(publisher).publish(any(VideoStatusChangedEvent.class), anyString());

        relay(claimService, outboxMapper, publisher, jsonUtils).relay();

        verify(outboxMapper).markPending(
                eq(EVENT_ID), eq(LEASE), any(LocalDateTime.class),
                contains("PUBLISH_CONFIRM_TIMEOUT"));
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
    }

    @Test
    void publishFailureAtRetryLimitIsMarkedFailedWithReason() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(9)), List.of());
        stubConsistentJson(jsonUtils);
        doThrow(new VideoStatusPublishException(
                VideoStatusPublishException.Reason.NACK, null))
                .when(publisher).publish(any(VideoStatusChangedEvent.class), anyString());

        relay(claimService, outboxMapper, publisher, jsonUtils).relay();

        verify(outboxMapper).markFailed(
                eq(EVENT_ID), eq(LEASE), contains("PUBLISH_NACK"));
        verify(outboxMapper, never()).markPending(anyString(), anyString(), any(), any());
        verify(outboxMapper, never()).markSent(anyString(), anyString(), any());
    }

    // -------------------------------------------------------------- CAS 与隔离

    @Test
    void markPendingZeroRowsIsDiagnosedWithoutFurtherStateChange() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        stubConsistentJson(jsonUtils);
        doThrow(new VideoStatusPublishException(
                VideoStatusPublishException.Reason.SEND_FAILED, null))
                .when(publisher).publish(any(VideoStatusChangedEvent.class), anyString());
        // 旧 owner 已失效：CAS 0 行
        when(outboxMapper.markPending(
                eq(EVENT_ID), eq(LEASE), any(LocalDateTime.class), anyString()))
                .thenReturn(0);

        assertDoesNotThrow(() -> relay(claimService, outboxMapper, publisher, jsonUtils).relay());

        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
        verify(outboxMapper, never()).markSent(anyString(), anyString(), any());
        verify(outboxMapper, never()).markConfirmedPending(anyString(), anyString(), any(), any());
    }

    @Test
    void markPendingThrowingDoesNotStopNextClaimedEventInSameRound() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        VideoStatusOutbox first = outbox(EVENT_ID, LEASE, 0);
        VideoStatusOutbox second = outbox(EVENT_ID_2, "lease-t07-next", 0);
        when(claimService.claimBatch(1)).thenReturn(List.of(first, second), List.of());
        stubConsistentJson(jsonUtils);
        doThrow(new VideoStatusPublishException(
                VideoStatusPublishException.Reason.SEND_FAILED, null))
                .when(publisher).publish(
                        org.mockito.ArgumentMatchers.argThat(
                                event -> event != null && EVENT_ID.equals(event.eventId())),
                        anyString());
        when(outboxMapper.markPending(
                eq(EVENT_ID), eq(LEASE), any(LocalDateTime.class), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> relay(claimService, outboxMapper, publisher, jsonUtils).relay());

        // 第二条事件仍被发布并成功回写
        verify(publisher).publish(
                org.mockito.ArgumentMatchers.argThat(
                        event -> event != null && EVENT_ID_2.equals(event.eventId())),
                eq("lease-t07-next"));
        verify(outboxMapper).markSent(
                eq(EVENT_ID_2), eq("lease-t07-next"), any(LocalDateTime.class));
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
    }

    @Test
    void recoverLeaseFailureDoesNotAbortRoundAndClaimStillRuns() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        stubConsistentJson(jsonUtils);
        when(outboxMapper.recoverExpiredSending(any(LocalDateTime.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        when(outboxMapper.markSent(eq(EVENT_ID), eq(LEASE), any())).thenReturn(1);

        assertDoesNotThrow(() -> relay(claimService, outboxMapper, publisher, jsonUtils).relay());

        // 恢复失败不能静默丢：保留状态并继续本轮 claim
        verify(claimService, org.mockito.Mockito.atLeastOnce()).claimBatch(1);
        verify(publisher).publish(any(VideoStatusChangedEvent.class), eq(LEASE));
        verify(outboxMapper).markSent(eq(EVENT_ID), eq(LEASE), any(LocalDateTime.class));
    }

    @Test
    void claimFailureEndsRoundWithoutThrowing() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertDoesNotThrow(() -> relay(claimService, outboxMapper, publisher, jsonUtils).relay());

        verifyNoInteractions(publisher);
        verify(outboxMapper, never()).markSent(anyString(), anyString(), any());
        verify(outboxMapper, never()).markPending(anyString(), anyString(), any(), any());
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------ 非法 payload / 事件

    @Test
    void invalidJsonPayloadIsMarkedInvalidFailedWithoutRetryOrPublish() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        // 解析异常 message 带 payload 片段，验证 last_error 只保留 reason + class
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenThrow(new BusinessException(
                        500, "JSON 转对象失败: {\"password\":\"p@ss\"}"));

        relay(claimService, outboxMapper, publisher, jsonUtils).relay();

        ArgumentCaptor<String> lastError = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).markInvalidFailed(eq(EVENT_ID), eq(LEASE), lastError.capture());
        assertTrue(lastError.getValue().contains("PAYLOAD_JSON_INVALID"));
        assertFalse(lastError.getValue().contains("p@ss"));
        assertFalse(lastError.getValue().contains("password"));
        verify(publisher, never()).publish(any(), anyString());
        verify(outboxMapper, never()).markPending(anyString(), anyString(), any(), any());
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
    }

    @Test
    void eventValidateFailureIsMarkedInvalidFailed() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        // hotScoreDelta 与 type/delta 不一致 → validate() 抛异常
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenReturn(new VideoStatusChangedEvent(EVENT_ID, 7,
                        VideoStatusEventType.PLAY, 1L, 99D,
                        LocalDateTime.now(), 2, null));

        relay(claimService, outboxMapper, publisher, jsonUtils).relay();

        verify(outboxMapper).markInvalidFailed(
                eq(EVENT_ID), eq(LEASE), contains("EVENT_INVALID"));
        verify(publisher, never()).publish(any(), anyString());
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
    }

    @Test
    void eventIdMismatchWithOutboxIsMarkedInvalidFailed() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenReturn(event("evt-other"));

        relay(claimService, outboxMapper, publisher, jsonUtils).relay();

        verify(outboxMapper).markInvalidFailed(
                eq(EVENT_ID), eq(LEASE), contains("EVENT_MISMATCH_EVENT_ID"));
        verify(publisher, never()).publish(any(), anyString());
    }

    @Test
    void vidAndTypeMismatchWithOutboxAreMarkedInvalidFailed() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        VideoStatusOutbox vidMismatch = outbox(EVENT_ID, LEASE, 0);
        VideoStatusOutbox typeMismatch = outbox(EVENT_ID_2, "lease-t07-next", 0);
        when(claimService.claimBatch(1))
                .thenReturn(List.of(vidMismatch, typeMismatch), List.of());
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenAnswer(invocation -> {
                    String eventId = invocation.getArgument(0);
                    if (EVENT_ID.equals(eventId)) {
                        // vid 不一致
                        return new VideoStatusChangedEvent(EVENT_ID, 8,
                                VideoStatusEventType.PLAY, 1L, 1D,
                                LocalDateTime.now(), 2, null);
                    }
                    // type 不一致
                    return new VideoStatusChangedEvent(EVENT_ID_2, 7,
                            VideoStatusEventType.LIKE, 1L, 1.5D,
                            LocalDateTime.now(), 2, null);
                });

        relay(claimService, outboxMapper, publisher, jsonUtils).relay();

        verify(outboxMapper).markInvalidFailed(
                eq(EVENT_ID), eq(LEASE), contains("EVENT_MISMATCH_VID"));
        verify(outboxMapper).markInvalidFailed(
                eq(EVENT_ID_2), eq("lease-t07-next"), contains("EVENT_MISMATCH_TYPE"));
        verify(publisher, never()).publish(any(), anyString());
    }

    // ------------------------------------------------------------------ 指标口径

    @Test
    void confirmedWritebackFailureStillRecordsPublishSuccessMetric() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);
        var telemetry = mock(VideoStatusTelemetry.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        stubConsistentJson(jsonUtils);
        when(outboxMapper.markSent(eq(EVENT_ID), eq(LEASE), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        relay(claimService, outboxMapper, publisher, jsonUtils, telemetry).relay();

        // 指标 SUCCESS = confirm 成功；持久化失败只体现在日志/状态上
        verify(telemetry).record(
                eq(VideoStatusTelemetry.Operation.PUBLISH),
                eq(VideoStatusTelemetry.Outcome.SUCCESS),
                anyLong());
        verify(telemetry, never()).record(
                eq(VideoStatusTelemetry.Operation.PUBLISH),
                eq(VideoStatusTelemetry.Outcome.FAILURE),
                anyLong());
    }

    @Test
    void invalidPayloadRecordsPublishFailureMetric() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);
        var telemetry = mock(VideoStatusTelemetry.class);

        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenThrow(new BusinessException(500, "bad payload"));

        relay(claimService, outboxMapper, publisher, jsonUtils, telemetry).relay();

        verify(telemetry).record(
                eq(VideoStatusTelemetry.Operation.PUBLISH),
                eq(VideoStatusTelemetry.Outcome.FAILURE),
                anyLong());
        verify(telemetry, never()).record(
                eq(VideoStatusTelemetry.Operation.PUBLISH),
                eq(VideoStatusTelemetry.Outcome.SUCCESS),
                anyLong());
    }

    // -------------------------------------------------------------- 开关与默认值

    @Test
    void relayIsDisabledWhenAsyncDisabled() {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);
        var properties = properties();
        properties.setAsyncEnabled(false);

        new VideoStatusOutboxRelay(claimService, outboxMapper, publisher,
                properties, jsonUtils).relay();

        verifyNoInteractions(claimService, publisher);
        verify(outboxMapper, never()).recoverExpiredSending(any());
    }

    @Test
    void relayFlagFalseStopsClaimRecoverAndPublishButKeepsAsyncOn() {
        // T04：outbox-relay-enabled 是独立的“停投”开关，也是 T07 FAILED 临时处置的暂停手段。
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);
        var properties = properties();
        assertTrue(properties.isAsyncEnabled());
        properties.setOutboxRelayEnabled(false);

        new VideoStatusOutboxRelay(claimService, outboxMapper, publisher,
                properties, jsonUtils).relay();

        verifyNoInteractions(claimService, publisher, jsonUtils);
        verify(outboxMapper, never()).recoverExpiredSending(any());
        verify(outboxMapper, never()).markSent(anyString(), anyString(), any());
        verify(outboxMapper, never()).markPending(anyString(), anyString(), any(), any());
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
        verify(outboxMapper, never()).markInvalidFailed(anyString(), anyString(), anyString());
    }

    @Test
    void relayFlagDefaultsToTrueSoExistingDeploymentsKeepPublishing() {
        assertTrue(new VideoStatusProperties().isOutboxRelayEnabled());

        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);
        when(claimService.claimBatch(1)).thenReturn(List.of());

        new VideoStatusOutboxRelay(claimService, outboxMapper, publisher,
                properties(), jsonUtils).relay();

        verify(claimService).claimBatch(1);
        verify(outboxMapper).recoverExpiredSending(any(LocalDateTime.class));
    }

    @Test
    void relayKeepsT06ClaimOneAndPerRoundBudget() throws Exception {
        VideoStatusProperties properties = properties();
        properties.setOutboxBatchSize(100);
        properties.setRoundBudgetMs(1);

        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);
        when(claimService.claimBatch(1)).thenReturn(List.of(outbox(0)), List.of());
        stubConsistentJson(jsonUtils);
        when(outboxMapper.markSent(eq(EVENT_ID), eq(LEASE), any())).thenReturn(1);
        // 模拟慢 publish：真实环境由 confirm timeout 界定
        org.mockito.Mockito.doAnswer(call -> {
            Thread.sleep(25L);
            return null;
        }).when(publisher).publish(any(VideoStatusChangedEvent.class), anyString());

        new VideoStatusOutboxRelay(claimService, outboxMapper, publisher,
                properties, jsonUtils).relay();

        // 预算检查在“下一次领取之前”：本轮只领一次，且仍逐条领取
        verify(claimService, times(1)).claimBatch(1);
        verify(claimService, never()).claimBatch();
        verify(publisher, times(1)).publish(any(VideoStatusChangedEvent.class), eq(LEASE));
    }
}
