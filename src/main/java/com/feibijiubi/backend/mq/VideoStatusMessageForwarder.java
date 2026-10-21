package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
public class VideoStatusMessageForwarder {

    private final RabbitTemplate rabbitTemplate;
    private final VideoStatusProperties properties;

    /**
     * T05 只读观测：允许为 null（纯构造测试/未装配指标的隔离配置）。
     * 指标失败不得改变转发结果与抛出的异常类型。
     */
    @Autowired(required = false)
    private VideoStatusTelemetry telemetry;

    public void toRetry(Message original, int nextAttempt) throws Exception {
        Message retryMessage = MessageBuilder.fromClonedMessage(original)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader(RabbitConstants.ATTEMPT_HEADER, nextAttempt)
                .build();
        publishConfirmed(
                VideoStatusTelemetry.Operation.RETRY_FORWARD,
                RabbitConstants.RETRY_EXCHANGE,
                RabbitConstants.RETRY_ROUTING_KEY,
                retryMessage
        );
    }

    public void toDead(Message original, String reason) throws Exception {
        Message deadMessage = MessageBuilder.fromClonedMessage(original)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader(
                        RabbitConstants.FAILURE_REASON_HEADER,
                        safeReason(reason)
                )
                .build();
        publishConfirmed(
                VideoStatusTelemetry.Operation.DEAD_FORWARD,
                RabbitConstants.DEAD_EXCHANGE,
                RabbitConstants.DEAD_ROUTING_KEY,
                deadMessage
        );
    }

    public void toRetry(
            String eventId,
            String payload,
            int nextAttempt
    ) throws Exception {
        Message message = recoveryMessage(eventId, payload)
                .setHeader(RabbitConstants.ATTEMPT_HEADER, nextAttempt)
                .build();
        publishConfirmed(
                VideoStatusTelemetry.Operation.RETRY_FORWARD,
                RabbitConstants.RETRY_EXCHANGE,
                RabbitConstants.RETRY_ROUTING_KEY,
                message
        );
    }

    public void toDead(
            String eventId,
            String payload,
            String reason
    ) throws Exception {
        Message message = recoveryMessage(eventId, payload)
                .setHeader(RabbitConstants.FAILURE_REASON_HEADER,
                        safeReason(reason))
                .build();
        publishConfirmed(
                VideoStatusTelemetry.Operation.DEAD_FORWARD,
                RabbitConstants.DEAD_EXCHANGE,
                RabbitConstants.DEAD_ROUTING_KEY,
                message
        );
    }

    private void publishConfirmed(
            VideoStatusTelemetry.Operation operation,
            String exchange,
            String routingKey,
            Message message
    ) throws Exception {
        long startedAt = System.nanoTime();
        try {
            doPublishConfirmed(exchange, routingKey, message);
            recordForward(operation, VideoStatusTelemetry.Outcome.SUCCESS,
                    startedAt);
        } catch (TimeoutException e) {
            recordForward(operation, VideoStatusTelemetry.Outcome.TIMEOUT,
                    startedAt);
            throw e;
        } catch (Exception e) {
            recordForward(operation, VideoStatusTelemetry.Outcome.FAILURE,
                    startedAt);
            throw e;
        }
    }

    private void doPublishConfirmed(
            String exchange,
            String routingKey,
            Message message
    ) throws Exception {
        CorrelationData correlationData =
                new CorrelationData(UUID.randomUUID().toString());

        rabbitTemplate.send(
                exchange,
                routingKey,
                message,
                correlationData
        );

        CorrelationData.Confirm confirm = correlationData.getFuture().get(
                properties.getPublishConfirmTimeoutSeconds(),
                TimeUnit.SECONDS
        );
        if (!confirm.isAck()) {
            throw new IllegalStateException(
                    "RabbitMQ 转发收到 NACK: " + confirm.getReason()
            );
        }
        if (correlationData.getReturned() != null) {
            throw new IllegalStateException(
                    "RabbitMQ 转发消息无法路由: "
                            + correlationData.getReturned().getReplyText()
            );
        }
    }

    private void recordForward(
            VideoStatusTelemetry.Operation operation,
            VideoStatusTelemetry.Outcome outcome,
            long startedAt
    ) {
        VideoStatusTelemetry target = telemetry;
        if (target == null) {
            return;
        }
        try {
            target.record(
                    operation,
                    outcome,
                    System.nanoTime() - startedAt
            );
        } catch (RuntimeException ignored) {
            // 指标自身失败不得改变转发结果。
        }
    }

    private MessageBuilderSupport<Message> recoveryMessage(
            String eventId,
            String payload
    ) {
        return MessageBuilder.withBody(
                        payload.getBytes(StandardCharsets.UTF_8)
                )
                .setMessageId(eventId)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader(RabbitConstants.RECOVERY_HEADER, true)
                .setHeader(RabbitConstants.EVENT_ID_HEADER, eventId);
    }

    private String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown error";
        }
        return reason.length() <= 1000
                ? reason
                : reason.substring(0, 1000);
    }
}
