package com.feibijiubi.backend.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.NonRetryableMessageException;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.common.RepairRequiredMessageException;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.enums.RegistrationResult;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry;
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
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * T05 只读观测：允许为 null（纯构造测试/未装配指标的隔离配置）。
     * 指标失败不影响解析、业务处理、转发与 ACK/NACK 边界。
     */
    @Autowired(required = false)
    private VideoStatusTelemetry telemetry;

    /**
     * T08：转发失败时登记异步暂停/恢复。允许为 null 以保持纯单元测试和旧隔离上下文的
     * 构造兼容；生产 component scan 必定装配本 Bean。缺失时仍保留原消息不确认，
     * 不会退化成立即 requeue 热循环。
     */
    @Autowired(required = false)
    private VideoStatusConsumerRecoveryController recoveryController;

    @RabbitListener(
            id = RabbitConstants.CONSUMER_LISTENER_ID,
            queues = RabbitConstants.MAIN_QUEUE,
            containerFactory = "videoStatusListenerContainerFactory"
    )
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        VideoStatusChangedEvent event = null;
        long startedAt = System.nanoTime();

        try {
            event = parseAndValidate(message);
            process(event);
        } catch (RepairRequiredMessageException e) {
            recordConsume(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            forwardDeadThenAck(message, channel, deliveryTag, e);
            return;
        } catch (NonRetryableMessageException e) {
            recordConsume(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            forwardDeadThenAck(message, channel, deliveryTag, e);
            return;
        } catch (RetryableMessageException
                 | RedisOperationException
                 | DataAccessException e) {
            recordConsume(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            forwardRetryOrDeadThenAck(
                    message,
                    channel,
                    deliveryTag,
                    event,
                    e
            );
            return;
        } catch (Exception e) {
            recordConsume(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
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

        recordConsume(VideoStatusTelemetry.Outcome.SUCCESS, startedAt);
        // 业务处理已经成功。ACK 失败不能再把正常消息转入 Retry/DLQ；
        // 让 IOException 交给监听容器处理，连接恢复后由 eventId 幂等吸收重投。
        channel.basicAck(deliveryTag, false);
    }

    private void recordConsume(
            VideoStatusTelemetry.Outcome outcome,
            long startedAt
    ) {
        VideoStatusTelemetry target = telemetry;
        if (target == null) {
            return;
        }
        try {
            target.record(
                    VideoStatusTelemetry.Operation.CONSUME,
                    outcome,
                    System.nanoTime() - startedAt
            );
        } catch (RuntimeException ignored) {
            // 指标自身失败不得改变异常分类与 ACK 边界。
        }
    }

    private void process(VideoStatusChangedEvent event) {
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
            case COUNTER_OUT_OF_RANGE ->
                    throw new NonRetryableMessageException(
                            "Redis 视频统计计数超出 0..Long.MAX_VALUE 范围"
                    );
            case INVALID_FIELD, INVALID_REDIS_TYPE ->
                    throw new NonRetryableMessageException(
                            "Redis 视频统计数据结构非法"
                    );
        }
    }

    /**
     * 将消息转为对象，同时验证该对象的准确性
     * @param message
     * @return
     */
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
        } catch (Exception forwardError) {
            // 转发失败不是业务失败：不 ACK、不 NACK，保留在途消息。
            // 立即 requeue 会形成热循环；同步 stop 会在 listener 线程等待自己。
            // 交给恢复控制器在专用线程异步停止容器，channel 关闭后由 broker 重投未确认消息。
            requestConsumerRecovery(
                    "retry/dead forward failed: " + safeMessage(forwardError)
            );
            log.error(
                    "统计消息重试/死信转发失败，保留原消息未确认并请求异步暂停消费者",
                    forwardError
            );
            return;
        }

        // 转发成功与 ACK 严格分开：ACK 抛错只向容器传播，不再执行任何转发/NACK，
        // 更不会把一条已经正常处理成功的消息转进 DLQ。
        channel.basicAck(deliveryTag, false);
    }

    private void forwardDeadThenAck(
            Message message,
            Channel channel,
            long deliveryTag,
            Exception cause
    ) throws IOException {
        try {
            forwarder.toDead(message, safeMessage(cause));
        } catch (Exception forwardError) {
            requestConsumerRecovery(
                    "dead forward failed: " + safeMessage(forwardError)
            );
            log.error(
                    "统计消息死信转发失败，保留原消息未确认并请求异步暂停消费者",
                    forwardError
            );
            return;
        }

        channel.basicAck(deliveryTag, false);
    }

    private void requestConsumerRecovery(String reason) {
        VideoStatusConsumerRecoveryController controller = recoveryController;
        if (controller == null) {
            log.error(
                    "视频统计消费者恢复控制器未装配，原消息保持未确认，等待进程/容器恢复: {}",
                    reason
            );
            return;
        }
        controller.requestRecovery(reason);
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
