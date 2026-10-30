package com.feibijiubi.backend.videostats.mq;

import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * T08 分级选路：10 秒 / 30 秒 / 120 秒三个固定 TTL 队列使用独立 routing key。
 * 纯 Mockito，不建立 MQ 连接。
 */
class VideoStatusRetryTierRoutingTests {

    @Test
    void firstSecondAndLaterRetriesRouteToDifferentFixedTtlQueues()
            throws Exception {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        confirmAll(rabbitTemplate);
        VideoStatusMessageForwarder forwarder = forwarder(rabbitTemplate);

        Message original = message();
        forwarder.toRetry(original, 1);
        forwarder.toRetry(original, 2);
        forwarder.toRetry(original, 3);
        forwarder.toRetry(original, 6);

        verify(rabbitTemplate, times(1)).send(
                eq(RabbitConstants.RETRY_EXCHANGE),
                eq(RabbitConstants.RETRY_ROUTING_KEY),
                any(Message.class),
                any(CorrelationData.class)
        );
        verify(rabbitTemplate, times(1)).send(
                eq(RabbitConstants.RETRY_EXCHANGE),
                eq(RabbitConstants.RETRY_ROUTING_KEY_30S),
                any(Message.class),
                any(CorrelationData.class)
        );
        verify(rabbitTemplate, times(2)).send(
                eq(RabbitConstants.RETRY_EXCHANGE),
                eq(RabbitConstants.RETRY_ROUTING_KEY_120S),
                any(Message.class),
                any(CorrelationData.class)
        );
    }

    @Test
    void retryKeepsEventIdBodyAndAttemptHeaderAcrossTiering() throws Exception {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        confirmAll(rabbitTemplate);
        VideoStatusMessageForwarder forwarder = forwarder(rabbitTemplate);

        forwarder.toRetry(message(), 2);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(
                eq(RabbitConstants.RETRY_EXCHANGE),
                eq(RabbitConstants.RETRY_ROUTING_KEY_30S),
                captor.capture(),
                any(CorrelationData.class)
        );
        Message forwarded = captor.getValue();
        assertEquals("event-1", forwarded.getMessageProperties().getMessageId());
        assertEquals("{\"eventId\":\"event-1\"}",
                new String(forwarded.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(2, forwarded.getMessageProperties().getHeaders()
                .get(RabbitConstants.ATTEMPT_HEADER));
    }

    @Test
    void recoveryReplayUsesTheSameTierRouting() throws Exception {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        confirmAll(rabbitTemplate);
        VideoStatusMessageForwarder forwarder = forwarder(rabbitTemplate);

        forwarder.toRetry("event-1", "{\"eventId\":\"event-1\"}", 3);

        verify(rabbitTemplate).send(
                eq(RabbitConstants.RETRY_EXCHANGE),
                eq(RabbitConstants.RETRY_ROUTING_KEY_120S),
                any(Message.class),
                any(CorrelationData.class)
        );
    }

    private static VideoStatusMessageForwarder forwarder(
            RabbitTemplate rabbitTemplate
    ) {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setPublishConfirmTimeoutSeconds(1);
        return new VideoStatusMessageForwarder(rabbitTemplate, properties);
    }

    private static Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("event-1");
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(
                "{\"eventId\":\"event-1\"}".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8),
                properties
        );
    }

    private static void confirmAll(RabbitTemplate rabbitTemplate) {
        doAnswer(call -> {
            CorrelationData data = call.getArgument(3);
            data.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                anyString(),
                anyString(),
                any(Message.class),
                any(CorrelationData.class)
        );
    }
}
