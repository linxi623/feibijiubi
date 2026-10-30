package com.feibijiubi.backend.videostats.mq;

import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
public class VideoStatusEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final VideoStatusProperties properties;

    /**
     * 将视频状态事件发送到RabbitMQ，并同步等待RabbitMQ的发送确认。
     *
     * <p>T07：失败不再统一抛 {@code Exception}，而是抛
     * {@link VideoStatusPublishException} 并用 {@link VideoStatusPublishException.Reason}
     * 区分“发送阶段失败”（SEND_FAILED/NACK/UNROUTABLE）与“confirm 结果不确定”
     * （CONFIRM_TIMEOUT/CONFIRM_INTERRUPTED/CONFIRM_FAILED）。两类都由 relay 计入
     * 发布失败重试次数，但诊断和后续处置语义不同：confirm 不确定意味着消息可能已到达，
     * 只能依赖 eventId 幂等去重，不能当成“没发出去”。</p>
     *
     * <p>异常 message 只包含 reason code，不包含 payload / broker 原文。</p>
     *
     * @param event     已通过 {@code validate()} 与 Outbox 语义一致性校验的事件
     * @param attemptId 处理该消息的租约令牌，用于 CorrelationData 追踪本次尝试
     */
    public void publish(VideoStatusChangedEvent event, String attemptId) {
        CorrelationData correlationData = new CorrelationData(
                event.eventId() + ":" + attemptId
        );

        try {
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
        } catch (RuntimeException e) {
            throw new VideoStatusPublishException(
                    VideoStatusPublishException.Reason.SEND_FAILED,
                    e
            );
        }

        CorrelationData.Confirm confirm;
        try {
            confirm = correlationData.getFuture().get(
                    properties.getPublishConfirmTimeoutSeconds(),
                    TimeUnit.SECONDS
            );
        } catch (TimeoutException e) {
            throw new VideoStatusPublishException(
                    VideoStatusPublishException.Reason.CONFIRM_TIMEOUT,
                    e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VideoStatusPublishException(
                    VideoStatusPublishException.Reason.CONFIRM_INTERRUPTED,
                    e
            );
        } catch (ExecutionException e) {
            throw new VideoStatusPublishException(
                    VideoStatusPublishException.Reason.CONFIRM_FAILED,
                    e
            );
        } catch (RuntimeException e) {
            // 例如 future 被取消：同样属于 confirm 结果不确定。
            throw new VideoStatusPublishException(
                    VideoStatusPublishException.Reason.CONFIRM_FAILED,
                    e
            );
        }

        if (!confirm.isAck()) {
            throw new VideoStatusPublishException(
                    VideoStatusPublishException.Reason.NACK,
                    null
            );
        }
        if (correlationData.getReturned() != null) {
            throw new VideoStatusPublishException(
                    VideoStatusPublishException.Reason.UNROUTABLE,
                    null
            );
        }
    }
}
