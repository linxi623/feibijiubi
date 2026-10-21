package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusOutbox;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatusOutboxRelay {

    private static final int MAX_PUBLISH_RETRIES = 10;

    private final VideoStatusOutboxClaimService claimService;
    private final VideoStatusOutboxMapper outboxMapper;
    private final VideoStatusEventPublisher publisher;
    private final VideoStatusProperties properties;
    private final JsonUtils jsonUtils;

    /**
     * T05 只读观测：允许为 null（纯构造测试/未装配指标的隔离配置）。
     * 指标失败只丢样本，不影响领取、发布与状态回写。
     */
    @Autowired(required = false)
    private VideoStatusTelemetry telemetry;

    /**
     * 从消息表中定期扫描待发送的消息并发布。
     *
     * <p>投递角色受两个开关约束：{@code async-enabled}（异步链路总开关，保持既有含义）
     * 与 {@code outbox-relay-enabled}（本实例是否承担投递）。任一为 false 时本方法直接返回，
     * 不领取、不恢复租约、不发布；消费者、批量落库与 dirty 恢复不受该开关影响，
     * 因此可以安全地把“停投”做成独立维护动作。</p>
     */
    @Scheduled(
            fixedDelayString =
                    "${app.video-status.outbox-fixed-delay-ms:1000}"
    )
    public void relay() {
        if (!isRelayRoleEnabled()) {
            return;
        }

        heartbeat(VideoStatusTelemetry.Operation.RELAY_TICK);
        recoverExpiredLease();
        List<VideoStatusOutbox> claimed = claimService.claimBatch();
        for (VideoStatusOutbox outbox : claimed) {
            publishOne(outbox);
        }
    }

    /**
     * 发布消息，并标记消息发布的状态
     * @param outbox
     */
    private void publishOne(VideoStatusOutbox outbox) {
        long startedAt = System.nanoTime();
        try {
            VideoStatusChangedEvent event = jsonUtils.fromJson(
                    outbox.getPayload(),
                    VideoStatusChangedEvent.class
            );
            publisher.publish(event, outbox.getLeaseToken());
            outboxMapper.markSent(
                    outbox.getEventId(),
                    outbox.getLeaseToken(),
                    LocalDateTime.now()
            );
            recordPublish(VideoStatusTelemetry.Outcome.SUCCESS, startedAt);
        } catch (Exception e) {
            String error = truncate(e.getMessage());
            if (outbox.getRetryCount() + 1 >= MAX_PUBLISH_RETRIES) {
                outboxMapper.markFailed(
                        outbox.getEventId(),
                        outbox.getLeaseToken(),
                        error
                );
                recordPublish(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
                log.error("视频统计 Outbox 发布最终失败, eventId={}",
                        outbox.getEventId(), e);
                return;
            }

            long delaySeconds = Math.min(
                    300,
                    1L << Math.min(outbox.getRetryCount(), 8)
            );
            outboxMapper.markPending(
                    outbox.getEventId(),
                    outbox.getLeaseToken(),
                    LocalDateTime.now().plusSeconds(delaySeconds),
                    error
            );
            recordPublish(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            log.warn("视频统计 Outbox 发布失败，将重试, eventId={}",
                    outbox.getEventId(), e);
        }
    }

    private void heartbeat(VideoStatusTelemetry.Operation operation) {
        VideoStatusTelemetry target = telemetry;
        if (target == null) {
            return;
        }
        try {
            target.heartbeat(operation);
        } catch (RuntimeException ignored) {
            // 心跳失败不影响调度。
        }
    }

    private void recordPublish(
            VideoStatusTelemetry.Outcome outcome,
            long startedAt
    ) {
        VideoStatusTelemetry target = telemetry;
        if (target == null) {
            return;
        }
        try {
            target.record(
                    VideoStatusTelemetry.Operation.PUBLISH,
                    outcome,
                    System.nanoTime() - startedAt
            );
        } catch (RuntimeException ignored) {
            // 指标自身失败不得改变发布/重试/失败分类。
        }
    }

    private boolean isRelayRoleEnabled() {
        return properties.isAsyncEnabled()
                && properties.isOutboxRelayEnabled();
    }

    // 恢复已经超时的Outbox消息租约，避免消息一直卡在“发送中”的状态
    private void recoverExpiredLease() {
        LocalDateTime expiredBefore = LocalDateTime.now()
                .minusSeconds(properties.getOutboxLeaseSeconds());
        outboxMapper.recoverExpiredSending(expiredBefore);
    }

    private String truncate(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() <= 1000
                ? message
                : message.substring(0, 1000);
    }
}
