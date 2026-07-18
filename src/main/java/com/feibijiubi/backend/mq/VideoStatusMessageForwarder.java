package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class VideoStatusMessageForwarder {

    private final RabbitTemplate rabbitTemplate;
    private final VideoStatusProperties properties;

    public void toRetry(Message original) throws Exception {
        publishConfirmed(
                RabbitConstants.RETRY_EXCHANGE,
                RabbitConstants.RETRY_ROUTING_KEY,
                original
        );
    }

    public void toDead(Message original, String reason) throws Exception {
        original.getMessageProperties().setHeader(
                "x-video-Status-failure-reason",
                reason
        );
        publishConfirmed(
                RabbitConstants.DEAD_EXCHANGE,
                RabbitConstants.DEAD_ROUTING_KEY,
                original
        );
    }

    private void publishConfirmed(
            String exchange,
            String routingKey,
            Message message
    ) throws Exception {
        message.getMessageProperties()
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
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
        if (!confirm.isAck() || correlationData.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ 转发未被可靠确认");
        }
    }
}
