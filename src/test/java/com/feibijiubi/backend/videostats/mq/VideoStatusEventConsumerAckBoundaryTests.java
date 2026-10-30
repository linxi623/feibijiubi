package com.feibijiubi.backend.videostats.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.videostats.exception.NonRetryableMessageException;
import com.feibijiubi.backend.videostats.exception.RetryableMessageException;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.enums.RegistrationResult;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.videostats.service.VideoStatusConsumptionService;
import com.feibijiubi.backend.videostats.service.VideoStatusEventAgeGuard;
import com.feibijiubi.backend.videostats.service.VideoStatusEventFingerprintService;
import com.feibijiubi.backend.videostats.service.VideoStatusService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T08 ACK 边界：ACK 异常与业务/转发异常必须分开，成功消息的 ACK 失败不得转为 DLQ；
 * 转发失败不立即 requeue，而是请求异步暂停容器。
 */
class VideoStatusEventConsumerAckBoundaryTests {

    private ObjectMapper objectMapper;
    private VideoStatusEventFingerprintService fingerprintService;
    private VideoStatusConsumptionService consumptionService;
    private VideoStatusService videoStatusService;
    private VideoStatusMessageForwarder forwarder;
    private VideoStatusConsumerRecoveryController recoveryController;
    private VideoStatusEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = mock(ObjectMapper.class);
        fingerprintService = mock(VideoStatusEventFingerprintService.class);
        consumptionService = mock(VideoStatusConsumptionService.class);
        videoStatusService = mock(VideoStatusService.class);
        forwarder = mock(VideoStatusMessageForwarder.class);
        recoveryController = mock(VideoStatusConsumerRecoveryController.class);

        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setConsumerMaxRetries(5);
        consumer = new VideoStatusEventConsumer(
                objectMapper,
                fingerprintService,
                consumptionService,
                videoStatusService,
                forwarder,
                properties
        );
        ReflectionTestUtils.setField(consumer, "recoveryController",
                recoveryController);
    }

    @Test
    void successfulBusinessAckFailureRequestsRecoveryWithoutDlqOrRetry() throws Exception {
        stubSuccessfulApply();
        Channel channel = mock(Channel.class);
        doThrow(new IOException("ack failed")).when(channel)
                .basicAck(7L, false);

        consumer.consume(message(7L), channel);

        verify(forwarder, never()).toRetry(any(Message.class), anyInt());
        verify(forwarder, never()).toDead(any(Message.class), anyString());
        verify(channel, never()).basicNack(7L, false, true);
        // 已成功的消息不能交给容器 reject 进 DLQ；保留未确认并请求异步暂停。
        verify(recoveryController).requestRecovery(anyString());
    }

    @Test
    void retryForwardSuccessThenAckFailureDoesNotPublishDeadOrNack()
            throws Exception {
        stubRetryableFailure();
        Channel channel = mock(Channel.class);
        doThrow(new IOException("ack failed")).when(channel)
                .basicAck(9L, false);

        consumer.consume(message(9L), channel);

        verify(forwarder).toRetry(any(Message.class), eq(1));
        verify(forwarder, never()).toDead(any(Message.class), anyString());
        verify(channel, never()).basicNack(9L, false, true);
        // ACK 故障不是转发故障；不重复转发，只请求一次受控恢复。
        verify(recoveryController).requestRecovery(anyString());
    }

    @Test
    void deadForwardSuccessThenAckFailureDoesNotForwardAgainOrNack()
            throws Exception {
        when(objectMapper.readValue(any(byte[].class),
                eq(VideoStatusChangedEvent.class)))
                .thenThrow(new IllegalArgumentException("bad payload"));
        Channel channel = mock(Channel.class);
        doThrow(new IOException("ack failed")).when(channel)
                .basicAck(11L, false);

        consumer.consume(message(11L), channel);

        verify(forwarder).toDead(any(Message.class), anyString());
        verify(forwarder, never()).toRetry(any(Message.class), anyInt());
        verify(channel, never()).basicNack(11L, false, true);
        verify(recoveryController).requestRecovery(anyString());
    }

    @Test
    void retryForwardFailureLeavesMessageUnackedAndRequestsAsyncPause()
            throws Exception {
        stubRetryableFailure();
        doThrow(new IllegalStateException("mq down"))
                .when(forwarder).toRetry(any(Message.class), eq(1));
        Channel channel = mock(Channel.class);

        consumer.consume(message(13L), channel);

        verify(recoveryController).requestRecovery(anyString());
        verify(channel, never()).basicAck(13L, false);
        verify(channel, never()).basicNack(13L, false, true);
    }

    @Test
    void nonRetryablePayloadGoesDeadThenAckExactlyOnce() throws Exception {
        when(objectMapper.readValue(any(byte[].class),
                eq(VideoStatusChangedEvent.class)))
                .thenThrow(new IllegalArgumentException("bad payload"));
        Channel channel = mock(Channel.class);

        consumer.consume(message(15L), channel);

        verify(forwarder).toDead(any(Message.class), anyString());
        verify(channel).basicAck(15L, false);
        verify(channel, never()).basicNack(15L, false, true);
    }

    @Test
    void staleEventRejectionGoesDeadThenAckExactlyOnce() throws Exception {
        stubStaleEventRejection();
        Channel channel = mock(Channel.class);

        consumer.consume(message(17L), channel);

        // T14-S1：30 天年龄拒绝走既有 NonRetryable → DLQ + ACK 边界，
        // 不是静默 ACK，也不进入 retry 队列。
        verify(forwarder).toDead(
                any(Message.class),
                eq(VideoStatusEventAgeGuard.EXPIRED_REASON)
        );
        verify(forwarder, never()).toRetry(any(Message.class), anyInt());
        verify(channel).basicAck(17L, false);
        verify(channel, never()).basicNack(17L, false, true);
        verify(videoStatusService, never()).apply(any());
        // NonRetryable 路径不写消费失败记录（只有可重试路径才记）。
        verify(consumptionService, never())
                .recordConsumerFailure(anyString(), anyInt(), anyString());
    }

    private void stubSuccessfulApply() throws Exception {
        VideoStatusChangedEvent event = event();
        when(objectMapper.readValue(any(byte[].class),
                eq(VideoStatusChangedEvent.class))).thenReturn(event);
        when(fingerprintService.hash(event)).thenReturn("a".repeat(64));
        when(consumptionService.register(event, "a".repeat(64)))
                .thenReturn(RegistrationResult.NEEDS_REDIS_APPLY);
        when(videoStatusService.apply(event))
                .thenReturn(VideoStatusService.ApplyResult.APPLIED);
    }

    private void stubRetryableFailure() throws Exception {
        VideoStatusChangedEvent event = event();
        when(objectMapper.readValue(any(byte[].class),
                eq(VideoStatusChangedEvent.class))).thenReturn(event);
        when(fingerprintService.hash(event)).thenReturn("b".repeat(64));
        when(consumptionService.register(event, "b".repeat(64)))
                .thenThrow(new RetryableMessageException("redis down"));
    }

    private void stubStaleEventRejection() throws Exception {
        VideoStatusChangedEvent event = event();
        when(objectMapper.readValue(any(byte[].class),
                eq(VideoStatusChangedEvent.class))).thenReturn(event);
        when(fingerprintService.hash(event)).thenReturn("c".repeat(64));
        when(consumptionService.register(event, "c".repeat(64)))
                .thenThrow(new NonRetryableMessageException(
                        VideoStatusEventAgeGuard.EXPIRED_REASON));
    }

    private static VideoStatusChangedEvent event() {
        return new VideoStatusChangedEvent(
                "e-1",
                42,
                VideoStatusEventType.PLAY,
                1L,
                VideoStatusEventType.PLAY.calculateHotScore(1L),
                LocalDateTime.now(),
                2,
                null
        );
    }

    private static Message message(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                properties);
    }
}
