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

    /**
     * 将视频状态事件发送到RabbitMQ，并同步等待RabbitMQ的发送确认。
     * 若交换机拒绝消息、确认超时或消息无法路由到队列，就抛出异常
     * @param event
     * @param attemptId 处理该消息的线程唯一标识
     * @throws Exception
     */
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
