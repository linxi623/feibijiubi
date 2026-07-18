package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class VideoStatusEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final VideoStatusProperties properties;

    public void publish(VideoStatusChangedEvent event, String attemptId)
            throws Exception {
        CorrelationData correlationData = new CorrelationData(
                event.eventId() + ":" + attemptId
        );

        rabbitTemplate.convertAndSend(
                RabbitConstants.MAIN_EXCHANGE,
                RabbitConstants.MAIN_ROUTING_KEY,
                event,
                message -> {
                    message.getMessageProperties()
                            .setMessageId(event.eventId());
                    message.getMessageProperties()
                            .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties()
                            .setContentType("application/json");
                    return message;
                },
                correlationData
        );

        CorrelationData.Confirm confirm = correlationData.getFuture().get(
                properties.getPublishConfirmTimeoutSeconds(),
                TimeUnit.SECONDS
        );
        if (!confirm.isAck()) {
            throw new IllegalStateException(
                    "RabbitMQ NACK: " + confirm.getReason()
            );
        }
        if (correlationData.getReturned() != null) {
            throw new IllegalStateException(
                    "RabbitMQ 消息未路由到队列: "
                            + correlationData.getReturned().getReplyText()
            );
        }
    }
}
