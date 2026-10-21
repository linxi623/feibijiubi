package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusOutbox;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.mq.VideoStatusEventPublisher;
import com.feibijiubi.backend.mq.VideoStatusOutboxClaimService;
import com.feibijiubi.backend.mq.VideoStatusOutboxRelay;
import com.feibijiubi.backend.utils.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T01 基线：Outbox 发布成功但 markSent 回写失败的故障窗口。
 *
 * <p>读代码确认（{@code VideoStatusOutboxRelay#publishOne}）：publish 与 markSent 位于同一个
 * try 块，markSent 抛异常会走“发布失败”分支——markPending + retry_count+1 + 指数退避，
 * 消息随后会被再次发布（at-least-once），依赖消费端 eventId 幂等去重。
 * 这里固定该行为，避免以后把“回写失败”误判为消息丢失。</p>
 */
class VideoStatusOutboxRelayTests {

    private static final String EVENT_ID = "evt-mark-sent";
    private static final String LEASE = "lease-1";

    private VideoStatusOutbox outbox(int retryCount) {
        VideoStatusOutbox outbox = new VideoStatusOutbox();
        outbox.setId(1L);
        outbox.setEventId(EVENT_ID);
        outbox.setAggregateId(7);
        outbox.setEventType("PLAY");
        outbox.setPayload("{}");
        outbox.setStatus(1);
        outbox.setRetryCount(retryCount);
        outbox.setLeaseToken(LEASE);
        outbox.setSendingAt(LocalDateTime.now());
        return outbox;
    }

    private VideoStatusChangedEvent event() {
        return new VideoStatusChangedEvent(EVENT_ID, 7, VideoStatusEventType.PLAY,
                1L, 1D, LocalDateTime.now(), 2, null);
    }

    private VideoStatusProperties properties() {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        return properties;
    }

    @Test
    void markSentFailureReturnsOutboxToPendingForDuplicateSafeRetry() throws Exception {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch()).thenReturn(List.of(outbox(0)));
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenReturn(event());
        // 发布成功（已到达 RabbitMQ），但状态回写失败
        when(outboxMapper.markSent(eq(EVENT_ID), eq(LEASE), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                        "injected markSent failure"));

        var relay = new VideoStatusOutboxRelay(
                claimService, outboxMapper, publisher, properties(), jsonUtils);
        relay.relay();

        verify(publisher).publish(any(VideoStatusChangedEvent.class), eq(LEASE));
        // 回写失败 → 回到 PENDING，带退避时间与错误摘要，随后会重复投递
        verify(outboxMapper).markPending(eq(EVENT_ID), eq(LEASE),
                any(LocalDateTime.class), contains("markSent failure"));
        // 尚未达到重试上限，不允许直接判 FAILED
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
    }

    @Test
    void markSentReturningZeroRowsIsCurrentlyIgnoredAndLeftToLeaseRecovery() throws Exception {
        // 特征化测试（未证实为缺陷）：markSent 影响 0 行时 relay 不检查返回值，
        // 行停留在 SENDING，只能等 recoverExpiredSending 兜底。T07 决定是否显式处理。
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch()).thenReturn(List.of(outbox(0)));
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenReturn(event());
        when(outboxMapper.markSent(eq(EVENT_ID), eq(LEASE), any())).thenReturn(0);

        new VideoStatusOutboxRelay(claimService, outboxMapper, publisher,
                properties(), jsonUtils).relay();

        verify(publisher).publish(any(VideoStatusChangedEvent.class), eq(LEASE));
        verify(outboxMapper, never()).markPending(anyString(), anyString(), any(), any());
        verify(outboxMapper, never()).markFailed(anyString(), anyString(), anyString());
        // 只依赖租约超时恢复
        verify(outboxMapper).recoverExpiredSending(any(LocalDateTime.class));
    }

    @Test
    void publishFailureAtRetryLimitIsMarkedFailedNotSilentlyDropped() throws Exception {
        var claimService = mock(VideoStatusOutboxClaimService.class);
        var outboxMapper = mock(VideoStatusOutboxMapper.class);
        var publisher = mock(VideoStatusEventPublisher.class);
        var jsonUtils = mock(JsonUtils.class);

        when(claimService.claimBatch()).thenReturn(List.of(outbox(9)));
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenReturn(event());
        doThrow(new IllegalStateException("broker down"))
                .when(publisher).publish(any(VideoStatusChangedEvent.class), anyString());

        new VideoStatusOutboxRelay(claimService, outboxMapper, publisher,
                properties(), jsonUtils).relay();

        verify(outboxMapper).markFailed(eq(EVENT_ID), eq(LEASE), contains("broker down"));
        verify(outboxMapper, never()).markPending(anyString(), anyString(), any(), any());
    }

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
}
