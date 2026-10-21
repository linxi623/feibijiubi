package com.feibijiubi.backend.service.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.common.VideoStatusFlushDeferredException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.entity.VideoStatusOutbox;
import com.feibijiubi.backend.enums.RegistrationResult;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry.Operation;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry.Outcome;
import com.feibijiubi.backend.mq.VideoStatusBatchFlushScheduler;
import com.feibijiubi.backend.mq.VideoStatusDirtyRecoveryScheduler;
import com.feibijiubi.backend.mq.VideoStatusEventConsumer;
import com.feibijiubi.backend.mq.VideoStatusEventPublisher;
import com.feibijiubi.backend.mq.VideoStatusMessageForwarder;
import com.feibijiubi.backend.mq.VideoStatusOutboxClaimService;
import com.feibijiubi.backend.mq.VideoStatusOutboxRelay;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusBatchFlushServiceImpl;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusRebuildServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusConsumptionService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusEventFingerprintService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildSnapshotService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T05 业务插桩单测：只验证“观测点是否在正确的时机、用正确的 Operation/Outcome 上报”，
 * 以及“指标失败绝不改变业务结果”。
 *
 * <p>约束：不启动 Spring 上下文、不连 MySQL/Redis/RabbitMQ，全部使用 mock 与
 * 纯内存对象；每个用例都断言业务结果（异常类型/ACK/状态推进）与观测互不影响。</p>
 */
class VideoStatusTelemetryInstrumentationTests {

    private static final int VID = 42;
    private static final String GATE_KEY = RedisKeyUtils.videoStatusRebuildGate(VID);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------------------------------------------------------------- vid 锁

    @Test
    void lockWaitSuccessIsRecordedWithElapsedNanos() throws Exception {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        RLock lock = mock(RLock.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        VideoStatusVidMutex mutex = new VideoStatusVidMutex(redisson);
        ReflectionTestUtils.setField(mutex, "telemetry", telemetry);

        String result = mutex.withLock(VID, (Supplier<String>) () -> "ok");

        assertEquals("ok", result);
        verify(telemetry).record(eq(Operation.LOCK), eq(Outcome.SUCCESS), anyLong());
        verify(lock).unlock();
    }

    @Test
    void lockTimeoutIsRecordedAsTimeoutAndStillThrows() throws Exception {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        RLock lock = mock(RLock.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        VideoStatusVidMutex mutex = new VideoStatusVidMutex(redisson);
        ReflectionTestUtils.setField(mutex, "telemetry", telemetry);

        Runnable action = () -> {
        };
        assertThrows(RetryableMessageException.class,
                () -> mutex.withLock(VID, action));
        verify(telemetry).record(eq(Operation.LOCK), eq(Outcome.TIMEOUT), anyLong());
        verify(lock, never()).unlock();
    }

    @Test
    void lockInterruptIsRecordedAsFailureAndKeepsInterruptFlag() throws Exception {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        RLock lock = mock(RLock.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(anyLong(), any(TimeUnit.class)))
                    .thenThrow(new InterruptedException("interrupted"));

            VideoStatusVidMutex mutex = new VideoStatusVidMutex(redisson);
            ReflectionTestUtils.setField(mutex, "telemetry", telemetry);

            assertThrows(RetryableMessageException.class,
                    () -> mutex.withLock(VID, (Runnable) () -> {
                    }));
            verify(telemetry).record(eq(Operation.LOCK), eq(Outcome.FAILURE), anyLong());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    // ------------------------------------------------------------ Outbox 投递

    @Test
    void relayTickHeartbeatsAndRecordsPublishSuccess() throws Exception {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusOutboxClaimService claimService =
                mock(VideoStatusOutboxClaimService.class);
        VideoStatusOutboxMapper outboxMapper =
                mock(VideoStatusOutboxMapper.class);
        VideoStatusEventPublisher publisher =
                mock(VideoStatusEventPublisher.class);
        JsonUtils jsonUtils = mock(JsonUtils.class);

        VideoStatusOutbox outbox = outbox();
        when(claimService.claimBatch()).thenReturn(List.of(outbox));
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenReturn(event());

        VideoStatusOutboxRelay relay = relay(
                claimService,
                outboxMapper,
                publisher,
                jsonUtils,
                telemetry
        );
        relay.relay();

        verify(telemetry).heartbeat(Operation.RELAY_TICK);
        verify(telemetry).record(eq(Operation.PUBLISH), eq(Outcome.SUCCESS), anyLong());
        verify(publisher).publish(any(VideoStatusChangedEvent.class), eq("lease-1"));
        verify(outboxMapper).markSent(eq("e-1"), eq("lease-1"), any(LocalDateTime.class));
    }

    @Test
    void relayDisabledRecordsNoHeartbeatAndTouchesNothing() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusOutboxClaimService claimService =
                mock(VideoStatusOutboxClaimService.class);
        VideoStatusOutboxMapper outboxMapper =
                mock(VideoStatusOutboxMapper.class);
        VideoStatusEventPublisher publisher =
                mock(VideoStatusEventPublisher.class);
        JsonUtils jsonUtils = mock(JsonUtils.class);

        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setOutboxRelayEnabled(false);
        VideoStatusOutboxRelay relay = new VideoStatusOutboxRelay(
                claimService,
                outboxMapper,
                publisher,
                properties,
                jsonUtils
        );
        ReflectionTestUtils.setField(relay, "telemetry", telemetry);

        relay.relay();

        verifyNoInteractions(telemetry);
        verify(claimService, never()).claimBatch();
        verify(outboxMapper, never()).recoverExpiredSending(any(LocalDateTime.class));
    }

    @Test
    void relayPublishFailureIsRecordedAsFailureAndKeepsRetryClassification()
            throws Exception {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusOutboxClaimService claimService =
                mock(VideoStatusOutboxClaimService.class);
        VideoStatusOutboxMapper outboxMapper =
                mock(VideoStatusOutboxMapper.class);
        VideoStatusEventPublisher publisher =
                mock(VideoStatusEventPublisher.class);
        JsonUtils jsonUtils = mock(JsonUtils.class);

        VideoStatusOutbox outbox = outbox();
        outbox.setRetryCount(9);
        when(claimService.claimBatch()).thenReturn(List.of(outbox));
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenThrow(new IllegalStateException("bad payload"));

        VideoStatusOutboxRelay relay = relay(
                claimService,
                outboxMapper,
                publisher,
                jsonUtils,
                telemetry
        );
        relay.relay();

        verify(telemetry).record(eq(Operation.PUBLISH), eq(Outcome.FAILURE), anyLong());
        verify(outboxMapper).markFailed(eq("e-1"), eq("lease-1"), anyString());
        verify(outboxMapper, never()).markSent(anyString(), anyString(), any());
        verify(publisher, never()).publish(any(), anyString());
    }

    // -------------------------------------------------------------- 消费入口

    @Test
    void consumeSuccessRecordsSuccessBeforeAck() throws Exception {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        VideoStatusEventFingerprintService fingerprintService =
                mock(VideoStatusEventFingerprintService.class);
        VideoStatusConsumptionService consumptionService =
                mock(VideoStatusConsumptionService.class);
        VideoStatusService videoStatusService = mock(VideoStatusService.class);
        VideoStatusMessageForwarder forwarder =
                mock(VideoStatusMessageForwarder.class);

        VideoStatusChangedEvent event = event();
        when(objectMapper.readValue(any(byte[].class),
                eq(VideoStatusChangedEvent.class))).thenReturn(event);
        when(fingerprintService.hash(event)).thenReturn("a".repeat(64));
        when(consumptionService.register(event, "a".repeat(64)))
                .thenReturn(RegistrationResult.NEEDS_REDIS_APPLY);
        when(videoStatusService.apply(event))
                .thenReturn(VideoStatusService.ApplyResult.APPLIED);

        VideoStatusEventConsumer consumer = consumer(
                objectMapper,
                fingerprintService,
                consumptionService,
                videoStatusService,
                forwarder,
                telemetry
        );
        Channel channel = mock(Channel.class);
        consumer.consume(message(7L), channel);

        verify(telemetry).record(eq(Operation.CONSUME), eq(Outcome.SUCCESS), anyLong());
        verify(consumptionService).markRedisApplied("e-1");
        verify(channel).basicAck(7L, false);
        verify(forwarder, never()).toRetry(any(Message.class), anyInt());
        verify(forwarder, never()).toDead(any(Message.class), anyString());
    }

    @Test
    void consumeRetryableFailureRecordsFailureAndStillForwardsRetry() throws Exception {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        VideoStatusEventFingerprintService fingerprintService =
                mock(VideoStatusEventFingerprintService.class);
        VideoStatusConsumptionService consumptionService =
                mock(VideoStatusConsumptionService.class);
        VideoStatusService videoStatusService = mock(VideoStatusService.class);
        VideoStatusMessageForwarder forwarder =
                mock(VideoStatusMessageForwarder.class);

        VideoStatusChangedEvent event = event();
        when(objectMapper.readValue(any(byte[].class),
                eq(VideoStatusChangedEvent.class))).thenReturn(event);
        when(fingerprintService.hash(event)).thenReturn("b".repeat(64));
        when(consumptionService.register(event, "b".repeat(64)))
                .thenThrow(new RetryableMessageException("redis down"));

        VideoStatusEventConsumer consumer = consumer(
                objectMapper,
                fingerprintService,
                consumptionService,
                videoStatusService,
                forwarder,
                telemetry
        );
        Channel channel = mock(Channel.class);
        Message message = message(9L);
        consumer.consume(message, channel);

        verify(telemetry).record(eq(Operation.CONSUME), eq(Outcome.FAILURE), anyLong());
        verify(forwarder).toRetry(message, 1);
        verify(channel).basicAck(9L, false);
        verify(channel, never()).basicNack(9L, false, true);
    }

    // ---------------------------------------------------------- retry/DLQ 转发

    @Test
    void retryForwardSuccessIsRecorded() throws Exception {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        confirmWith(rabbitTemplate, new CorrelationData.Confirm(true, null));

        VideoStatusMessageForwarder forwarder = forwarder(rabbitTemplate, 1, telemetry);
        forwarder.toRetry(message(1L), 2);

        verify(telemetry).record(
                eq(Operation.RETRY_FORWARD),
                eq(Outcome.SUCCESS),
                anyLong()
        );
    }

    @Test
    void deadForwardNackIsRecordedAsFailureAndStillThrows() throws Exception {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        confirmWith(rabbitTemplate, new CorrelationData.Confirm(false, "nack"));

        VideoStatusMessageForwarder forwarder = forwarder(rabbitTemplate, 1, telemetry);

        assertThrows(IllegalStateException.class,
                () -> forwarder.toDead(message(1L), "reason"));
        verify(telemetry).record(
                eq(Operation.DEAD_FORWARD),
                eq(Outcome.FAILURE),
                anyLong()
        );
    }

    @Test
    void forwardConfirmTimeoutIsRecordedAsTimeoutAndStillThrows() throws Exception {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

        VideoStatusMessageForwarder forwarder = forwarder(rabbitTemplate, 0, telemetry);

        assertThrows(TimeoutException.class,
                () -> forwarder.toRetry("e-1", "{}", 1));
        verify(telemetry).record(
                eq(Operation.RETRY_FORWARD),
                eq(Outcome.TIMEOUT),
                anyLong()
        );
    }

    // ------------------------------------------------------------ 批量落库调度

    @Test
    void flushTickHeartbeatsAndRecordsSuccess() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusBatchFlushService flushService =
                mock(VideoStatusBatchFlushService.class);
        VideoStatusConsumedEventMapper consumedMapper =
                mock(VideoStatusConsumedEventMapper.class);
        when(consumedMapper.countPendingByVid(VID)).thenReturn(0);

        VideoStatusBatchFlushScheduler scheduler = scheduler(
                flushService,
                consumedMapper,
                telemetry
        );
        scheduler.flushDirtyVideos();

        verify(telemetry).heartbeat(Operation.FLUSH_TICK);
        verify(telemetry).record(eq(Operation.FLUSH), eq(Outcome.SUCCESS), anyLong());
    }

    @Test
    void flushDeferralIsRecordedAsDeferredAndKeepsDirty() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusBatchFlushService flushService =
                mock(VideoStatusBatchFlushService.class);
        VideoStatusConsumedEventMapper consumedMapper =
                mock(VideoStatusConsumedEventMapper.class);
        doThrow(new VideoStatusFlushDeferredException(VID, List.of(1L), "defer"))
                .when(flushService).flushOneVideo(anyInt(), anyInt());

        VideoStatusBatchFlushScheduler scheduler = scheduler(
                flushService,
                consumedMapper,
                telemetry
        );
        scheduler.flushDirtyVideos();

        verify(telemetry).record(eq(Operation.FLUSH), eq(Outcome.DEFERRED), anyLong());
        verify(telemetry, never()).record(
                eq(Operation.FLUSH),
                eq(Outcome.SUCCESS),
                anyLong()
        );
    }

    @Test
    void flushTransientFailureIsRecordedAsFailure() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusBatchFlushService flushService =
                mock(VideoStatusBatchFlushService.class);
        VideoStatusConsumedEventMapper consumedMapper =
                mock(VideoStatusConsumedEventMapper.class);
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(flushService).flushOneVideo(anyInt(), anyInt());

        VideoStatusBatchFlushScheduler scheduler = scheduler(
                flushService,
                consumedMapper,
                telemetry
        );
        scheduler.flushDirtyVideos();

        verify(telemetry).record(eq(Operation.FLUSH), eq(Outcome.FAILURE), anyLong());
    }

    // ------------------------------------------------------------ dirty 恢复调度

    @Test
    void recoveryTickHeartbeatsAndRespectsSwitch() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setSchedulingEnabled(true);
        VideoStatusConsumedEventMapper consumedMapper =
                mock(VideoStatusConsumedEventMapper.class);
        when(consumedMapper.selectPendingVids(anyInt())).thenReturn(List.of(VID));

        VideoStatusDirtyRecoveryScheduler scheduler =
                new VideoStatusDirtyRecoveryScheduler(
                        properties,
                        consumedMapper,
                        mock(RedisSetOperations.class)
                );
        ReflectionTestUtils.setField(scheduler, "telemetry", telemetry);
        scheduler.recoverDirtyVids();

        verify(telemetry).heartbeat(Operation.RECOVERY_TICK);

        VideoStatusTelemetry disabledTelemetry = mock(VideoStatusTelemetry.class);
        VideoStatusProperties disabled = new VideoStatusProperties();
        disabled.setAsyncEnabled(true);
        disabled.setSchedulingEnabled(false);
        VideoStatusDirtyRecoveryScheduler disabledScheduler =
                new VideoStatusDirtyRecoveryScheduler(
                        disabled,
                        consumedMapper,
                        mock(RedisSetOperations.class)
                );
        ReflectionTestUtils.setField(disabledScheduler, "telemetry", disabledTelemetry);
        disabledScheduler.recoverDirtyVids();

        verifyNoInteractions(disabledTelemetry);
    }

    // ------------------------------------------- 事件创建→FLUSHED 端到端延迟

    @Test
    void flushedLatencyIsRecordedOnlyAfterCommit() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusConsumedEventMapper consumedMapper =
                mock(VideoStatusConsumedEventMapper.class);
        VideoStatusMapper statusMapper = mock(VideoStatusMapper.class);
        String payload = "{\"eventId\":\"e-1\",\"occurredAt\":\"2026-01-01T00:00:00\"}";
        when(consumedMapper.selectPendingForUpdate(VID, 10))
                .thenReturn(List.of(pendingEvent(1L, 2L, payload)));
        when(statusMapper.applyBatchDelta(eq(VID), any())).thenReturn(1);
        when(consumedMapper.markFlushed(List.of(1L))).thenReturn(1);

        VideoStatusBatchFlushServiceImpl service =
                new VideoStatusBatchFlushServiceImpl(consumedMapper, statusMapper);
        ReflectionTestUtils.setField(service, "telemetry", telemetry);

        TransactionSynchronizationManager.initSynchronization();
        service.flushOneVideo(VID, 10);

        // 事务未提交前不得上报“成功落库延迟”。
        verify(telemetry, never()).recordFlushedPayload(anyString());

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertFalse(synchronizations.isEmpty(), "必须在事务同步器中登记 afterCommit");
        TransactionSynchronizationUtils.invokeAfterCommit(synchronizations);
        TransactionSynchronizationUtils.invokeAfterCompletion(
                synchronizations,
                TransactionSynchronization.STATUS_COMMITTED
        );

        verify(telemetry).recordFlushedPayload(payload);
    }

    @Test
    void flushedLatencyIsNotRecordedWhenTransactionRollsBack() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusConsumedEventMapper consumedMapper =
                mock(VideoStatusConsumedEventMapper.class);
        VideoStatusMapper statusMapper = mock(VideoStatusMapper.class);
        when(consumedMapper.selectPendingForUpdate(VID, 10))
                .thenReturn(List.of(pendingEvent(1L, 2L, "{\"occurredAt\":\"x\"}")));
        when(statusMapper.applyBatchDelta(eq(VID), any())).thenReturn(1);
        when(consumedMapper.markFlushed(List.of(1L))).thenReturn(1);

        VideoStatusBatchFlushServiceImpl service =
                new VideoStatusBatchFlushServiceImpl(consumedMapper, statusMapper);
        ReflectionTestUtils.setField(service, "telemetry", telemetry);

        TransactionSynchronizationManager.initSynchronization();
        service.flushOneVideo(VID, 10);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        // 回滚时 Spring 只调用 afterCompletion，不调用 afterCommit。
        TransactionSynchronizationUtils.invokeAfterCompletion(
                synchronizations,
                TransactionSynchronization.STATUS_ROLLED_BACK
        );

        verify(telemetry, never()).recordFlushedPayload(anyString());
    }

    @Test
    void brokenPayloadOrTelemetryFailureNeverBreaksCommittedFlush() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        doThrow(new IllegalStateException("坏 payload / 指标自身失败"))
                .when(telemetry).recordFlushedPayload(anyString());
        VideoStatusConsumedEventMapper consumedMapper =
                mock(VideoStatusConsumedEventMapper.class);
        VideoStatusMapper statusMapper = mock(VideoStatusMapper.class);
        when(consumedMapper.selectPendingForUpdate(VID, 10))
                .thenReturn(List.of(pendingEvent(1L, 2L, "not-a-json")));
        when(statusMapper.applyBatchDelta(eq(VID), any())).thenReturn(1);
        when(consumedMapper.markFlushed(List.of(1L))).thenReturn(1);

        VideoStatusBatchFlushServiceImpl service =
                new VideoStatusBatchFlushServiceImpl(consumedMapper, statusMapper);
        ReflectionTestUtils.setField(service, "telemetry", telemetry);

        TransactionSynchronizationManager.initSynchronization();
        service.flushOneVideo(VID, 10);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();

        assertDoesNotThrow(() ->
                TransactionSynchronizationUtils.invokeAfterCommit(synchronizations));
        verify(consumedMapper).markFlushed(List.of(1L));
    }

    @Test
    void flushWithoutTransactionContextDoesNotReportLatency() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusConsumedEventMapper consumedMapper =
                mock(VideoStatusConsumedEventMapper.class);
        VideoStatusMapper statusMapper = mock(VideoStatusMapper.class);
        when(consumedMapper.selectPendingForUpdate(VID, 10))
                .thenReturn(List.of(pendingEvent(1L, 2L, "{\"occurredAt\":\"x\"}")));
        when(statusMapper.applyBatchDelta(eq(VID), any())).thenReturn(1);
        when(consumedMapper.markFlushed(List.of(1L))).thenReturn(1);

        VideoStatusBatchFlushServiceImpl service =
                new VideoStatusBatchFlushServiceImpl(consumedMapper, statusMapper);
        ReflectionTestUtils.setField(service, "telemetry", telemetry);

        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        service.flushOneVideo(VID, 10);

        verify(consumedMapper).markFlushed(List.of(1L));
        verifyNoInteractions(telemetry);
    }

    // ------------------------------------------------------------------ 重建

    @Test
    void rebuildSuccessRecordsElapsedAndWritesTimestampedGateToken() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusRebuildSnapshotService snapshotService =
                mock(VideoStatusRebuildSnapshotService.class);
        RedisUtils redisUtils = mock(RedisUtils.class);
        when(redisUtils.hasKey(RedisKeyUtils.videoStatus(VID))).thenReturn(true);

        VideoStatusRebuildServiceImpl service =
                rebuildService(snapshotService, redisUtils, telemetry);

        long before = System.currentTimeMillis();
        service.ensureInitialized(VID);
        long after = System.currentTimeMillis();

        verify(telemetry).record(eq(Operation.REBUILD), eq(Outcome.SUCCESS), anyLong());

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisUtils).setString(eq(GATE_KEY), tokenCaptor.capture());
        String token = tokenCaptor.getValue();

        int separator = token.indexOf('-');
        assertTrue(separator > 0, "门闩 token 必须带 epochMillis 前缀: " + token);
        long createdMillis = Long.parseLong(token.substring(0, separator));
        assertTrue(createdMillis >= before && createdMillis <= after,
                "前缀必须是门闩创建时刻，才能按需观测停留时长");
        // 前缀之后仍是合法 UUID：整串只做相等比较，Lua 协议不变。
        UUID.fromString(token.substring(separator + 1));

        // 释放时用同一个 token 做相等比较（旧协议保持）。
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(redisUtils).executeScript(any(), eq(List.of(GATE_KEY)), args.capture());
        assertEquals(token, args.getValue());
    }

    @Test
    void rebuildFailureRecordsFailureAndStillThrows() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusRebuildSnapshotService snapshotService =
                mock(VideoStatusRebuildSnapshotService.class);
        RedisUtils redisUtils = mock(RedisUtils.class);
        when(redisUtils.hasKey(RedisKeyUtils.videoStatus(VID))).thenReturn(false);
        when(snapshotService.load(VID))
                .thenThrow(new RetryableMessageException("snapshot failed"));

        VideoStatusRebuildServiceImpl service =
                rebuildService(snapshotService, redisUtils, telemetry);

        assertThrows(RetryableMessageException.class,
                () -> service.ensureInitialized(VID));
        verify(telemetry).record(eq(Operation.REBUILD), eq(Outcome.FAILURE), anyLong());
        verify(telemetry, never()).record(
                eq(Operation.REBUILD),
                eq(Outcome.SUCCESS),
                anyLong()
        );
    }

    // ------------------------------------------------------------------ 工具

    private static VideoStatusOutboxRelay relay(
            VideoStatusOutboxClaimService claimService,
            VideoStatusOutboxMapper outboxMapper,
            VideoStatusEventPublisher publisher,
            JsonUtils jsonUtils,
            VideoStatusTelemetry telemetry
    ) {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setOutboxRelayEnabled(true);
        VideoStatusOutboxRelay relay = new VideoStatusOutboxRelay(
                claimService,
                outboxMapper,
                publisher,
                properties,
                jsonUtils
        );
        ReflectionTestUtils.setField(relay, "telemetry", telemetry);
        return relay;
    }

    private static VideoStatusEventConsumer consumer(
            ObjectMapper objectMapper,
            VideoStatusEventFingerprintService fingerprintService,
            VideoStatusConsumptionService consumptionService,
            VideoStatusService videoStatusService,
            VideoStatusMessageForwarder forwarder,
            VideoStatusTelemetry telemetry
    ) {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setConsumerMaxRetries(5);
        VideoStatusEventConsumer consumer = new VideoStatusEventConsumer(
                objectMapper,
                fingerprintService,
                consumptionService,
                videoStatusService,
                forwarder,
                properties
        );
        ReflectionTestUtils.setField(consumer, "telemetry", telemetry);
        return consumer;
    }

    private static VideoStatusMessageForwarder forwarder(
            RabbitTemplate rabbitTemplate,
            int confirmTimeoutSeconds,
            VideoStatusTelemetry telemetry
    ) {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setPublishConfirmTimeoutSeconds(confirmTimeoutSeconds);
        VideoStatusMessageForwarder forwarder =
                new VideoStatusMessageForwarder(rabbitTemplate, properties);
        ReflectionTestUtils.setField(forwarder, "telemetry", telemetry);
        return forwarder;
    }

    private static void confirmWith(
            RabbitTemplate rabbitTemplate,
            CorrelationData.Confirm confirm
    ) {
        doAnswer(call -> {
            CorrelationData data = call.getArgument(3);
            data.getFuture().complete(confirm);
            return null;
        }).when(rabbitTemplate).send(
                anyString(),
                anyString(),
                any(Message.class),
                any(CorrelationData.class)
        );
    }

    private static VideoStatusBatchFlushScheduler scheduler(
            VideoStatusBatchFlushService flushService,
            VideoStatusConsumedEventMapper consumedMapper,
            VideoStatusTelemetry telemetry
    ) {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setSchedulingEnabled(true);
        RedisSetOperations sets = mock(RedisSetOperations.class);
        when(sets.pop(anyString(), anyLong())).thenReturn(Set.of(String.valueOf(VID)));

        VideoStatusVidMutex vidMutex = mock(VideoStatusVidMutex.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(vidMutex).withLock(anyInt(), any(Runnable.class));

        VideoStatusBatchFlushScheduler scheduler = new VideoStatusBatchFlushScheduler(
                properties,
                sets,
                flushService,
                consumedMapper,
                vidMutex,
                Clock.systemUTC()
        );
        ReflectionTestUtils.setField(scheduler, "telemetry", telemetry);
        return scheduler;
    }

    private static VideoStatusRebuildServiceImpl rebuildService(
            VideoStatusRebuildSnapshotService snapshotService,
            RedisUtils redisUtils,
            VideoStatusTelemetry telemetry
    ) {
        VideoStatusVidMutex vidMutex = mock(VideoStatusVidMutex.class);
        // ensureInitialized 传入的 lambda 是 void 表达式，匹配 Runnable 重载。
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(vidMutex).withLock(anyInt(), any(Runnable.class));

        VideoStatusRebuildServiceImpl service = new VideoStatusRebuildServiceImpl(
                snapshotService,
                vidMutex,
                redisUtils
        );
        ReflectionTestUtils.setField(service, "telemetry", telemetry);
        return service;
    }

    private static VideoStatusOutbox outbox() {
        VideoStatusOutbox outbox = new VideoStatusOutbox();
        outbox.setEventId("e-1");
        outbox.setAggregateId(VID);
        outbox.setPayload("{}");
        outbox.setRetryCount(0);
        outbox.setLeaseToken("lease-1");
        return outbox;
    }

    private static VideoStatusChangedEvent event() {
        return new VideoStatusChangedEvent(
                "e-1",
                VID,
                VideoStatusEventType.PLAY,
                1L,
                VideoStatusEventType.PLAY.calculateHotScore(1L),
                LocalDateTime.now(),
                2,
                null
        );
    }

    private static VideoStatusConsumedEvent pendingEvent(
            long id,
            long delta,
            String payload
    ) {
        VideoStatusConsumedEvent event = new VideoStatusConsumedEvent();
        event.setId(id);
        event.setEventId("e-" + id);
        event.setVid(VID);
        event.setEventType("PLAY");
        event.setDelta(delta);
        event.setPayload(payload);
        event.setProcessStatus(1);
        return event;
    }

    private static Message message(long deliveryTag) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setDeliveryTag(deliveryTag);
        return new Message(new byte[0], messageProperties);
    }
}
