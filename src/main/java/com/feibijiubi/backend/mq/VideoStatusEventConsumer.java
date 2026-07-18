package com.feibijiubi.backend.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.NonRetryableMessageException;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.config.VideoStatusProperties;

import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.service.video.VideoStatusService;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatusEventConsumer {

    private final ObjectMapper objectMapper;
    private final VideoStatusService videoStatusService;
    private final VideoStatusMessageForwarder forwarder;
    private final VideoStatusProperties properties;

    @RabbitListener(
            queues = RabbitConstants.MAIN_QUEUE,
            containerFactory = "videoStatusListenerContainerFactory"
    )
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
            process(message);
        } catch (NonRetryableMessageException e) {
            forwardDeadThenAck(message, channel, deliveryTag, e);
            return;
        } catch (RetryableMessageException
                 | RedisOperationException
                 | DataAccessException e) {
            forwardRetryOrDeadThenAck(message, channel, deliveryTag, e);
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

    private void process(Message message) {
        VideoStatusChangedEvent event = parse(message);
        String payloadHash = sha256(message.getBody());

        VideoStatusService.ApplyResult redisResult =
                videoStatusService.apply(event);
        switch (redisResult) {
            case APPLIED, DUPLICATE ->
                    videoStatusService.persist(event, payloadHash);
            case SEQUENCE_GAP, NEEDS_REBUILD ->
                    throw new RetryableMessageException(
                            "统计事件存在序号缺口或需要重建"
                    );
            case OLD_SEQUENCE ->
                    throw new NonRetryableMessageException(
                            "旧序号事件没有对应的 eventId 幂等标记"
                    );
            case NEGATIVE_RESULT, INVALID_FIELD ->
                    throw new NonRetryableMessageException(
                            "统计事件会产生非法计数"
                    );
        }
    }

    private VideoStatusChangedEvent parse(Message message) {
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
            Exception cause
    ) throws IOException {
        try {
            if (retryCount(message) >= properties.getConsumerMaxRetries()) {
                forwarder.toDead(message, cause.getMessage());
            } else {
                forwarder.toRetry(message);
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
            forwarder.toDead(message, cause.getMessage());
            channel.basicAck(deliveryTag, false);
        } catch (Exception forwardError) {
            log.error("统计消息死信转发失败，保留原消息未确认", forwardError);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private long retryCount(Message message) {
        Object header = message.getMessageProperties()
                .getHeaders()
                .get("x-death");
        if (!(header instanceof List<?> deaths)) {
            return 0;
        }

        return deaths.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(death -> RabbitConstants.RETRY_QUEUE
                        .equals(String.valueOf(death.get("queue"))))
                .mapToLong(death -> {
                    Object count = death.get("count");
                    return count instanceof Number number
                            ? number.longValue()
                            : 0L;
                })
                .sum();
    }

    private String sha256(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(body);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new NonRetryableMessageException(
                    "统计消息摘要计算失败",
                    e
            );
        }
    }
}
