package com.feibijiubi.backend.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.NonRetryableMessageException;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.common.RepairRequiredMessageException;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.enums.RegistrationResult;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusConsumptionService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusEventFingerprintService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatusEventConsumer {

    private final ObjectMapper objectMapper;
    private final VideoStatusEventFingerprintService fingerprintService;
    private final VideoStatusConsumptionService consumptionService;
    private final VideoStatusService videoStatusService;
    private final VideoStatusMessageForwarder forwarder;
    private final VideoStatusProperties properties;
    private final VideoStatusVidMutex vidMutex;

    @RabbitListener(
            queues = RabbitConstants.MAIN_QUEUE,
            containerFactory = "videoStatusListenerContainerFactory"
    )
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        VideoStatusChangedEvent event = null;

        try {
            event = parseAndValidate(message);
            process(event);
        } catch (RepairRequiredMessageException e) {
            forwardDeadThenAck(message, channel, deliveryTag, e);
            return;
        } catch (NonRetryableMessageException e) {
            forwardDeadThenAck(message, channel, deliveryTag, e);
            return;
        } catch (RetryableMessageException
                 | RedisOperationException
                 | DataAccessException e) {
            forwardRetryOrDeadThenAck(
                    message,
                    channel,
                    deliveryTag,
                    event,
                    e
            );
            return;
        } catch (Exception e) {
            forwardDeadThenAck(
                    message,
                    channel,
                    deliveryTag,
                    new NonRetryableMessageException(
                            "未知或非法统计消息",
                            e
                    )
            );
            return;
        }

        // 业务处理已经成功。ACK 失败不能再把正常消息转入 Retry/DLQ；
        // 让 IOException 交给监听容器处理，连接恢复后由 eventId 幂等吸收重投。
        channel.basicAck(deliveryTag, false);
    }

    private void process(VideoStatusChangedEvent event) {
        vidMutex.withLock(event.vid(), () -> processLocked(event));
    }

    private void processLocked(VideoStatusChangedEvent event) {
        String payloadHash = fingerprintService.hash(event);
        RegistrationResult registration = consumptionService.register(
                event,
                payloadHash
        );

        if (registration == RegistrationResult.REDIS_ALREADY_APPLIED
                || registration == RegistrationResult.ALREADY_FLUSHED) {
            return;
        }

        VideoStatusService.ApplyResult redisResult =
                videoStatusService.apply(event);
        switch (redisResult) {
            case APPLIED, DUPLICATE ->
                    consumptionService.markRedisApplied(event.eventId());
            case NEEDS_REBUILD ->
                    throw new RetryableMessageException(
                            "Redis 初始化后仍无法应用视频统计事件"
                    );
            case NEGATIVE_RESULT ->
                    throw new RetryableMessageException(
                            "负增量暂时无法应用"
                    );
            case INVALID_FIELD, INVALID_REDIS_TYPE ->
                    throw new NonRetryableMessageException(
                            "Redis 视频统计数据结构非法"
                    );
        }
    }

    private VideoStatusChangedEvent parseAndValidate(Message message) {
        try {
            VideoStatusChangedEvent event = objectMapper.readValue(
                    message.getBody(),
                    VideoStatusChangedEvent.class
            );
            event.validate();
            return event;
        } catch (Exception e) {
            throw new NonRetryableMessageException("统计消息解析失败", e);
        }
    }

    private void forwardRetryOrDeadThenAck(
            Message message,
            Channel channel,
            long deliveryTag,
            VideoStatusChangedEvent event,
            Exception cause
    ) throws IOException {
        int nextAttempt = headerAttempt(message) + 1;
        if (event != null) {
            recordFailureBestEffort(event.eventId(), nextAttempt, cause);
        }

        try {
            if (nextAttempt <= properties.getConsumerMaxRetries()) {
                forwarder.toRetry(message, nextAttempt);
            } else {
                forwarder.toDead(message, safeMessage(cause));
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception forwardError) {
            log.error("统计消息重试转发失败，保留原消息未确认", forwardError);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void forwardDeadThenAck(
            Message message,
            Channel channel,
            long deliveryTag,
            Exception cause
    ) throws IOException {
        try {
            forwarder.toDead(message, safeMessage(cause));
            channel.basicAck(deliveryTag, false);
        } catch (Exception forwardError) {
            log.error("统计消息死信转发失败，保留原消息未确认", forwardError);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private int headerAttempt(Message message) {
        Object header = message.getMessageProperties().getHeaders()
                .get(RabbitConstants.ATTEMPT_HEADER);
        if (header instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (header instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                log.warn("非法的视频统计重试次数 Header: {}", text);
            }
        }
        return 0;
    }

    private void recordFailureBestEffort(
            String eventId,
            int attempt,
            Exception originalError
    ) {
        try {
            consumptionService.recordConsumerFailure(
                    eventId,
                    attempt,
                    safeMessage(originalError)
            );
        } catch (Exception recordError) {
            log.error(
                    "记录视频统计消费失败信息失败，不覆盖原始异常: eventId={}, attempt={}",
                    eventId,
                    attempt,
                    recordError
            );
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        if (message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }

}
