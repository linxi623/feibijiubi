package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusOutbox;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Scheduled(
            fixedDelayString =
                    "${app.video-Status.outbox-fixed-delay-ms:1000}"
    )
    public void relay() {
        if (!properties.isAsyncEnabled()) {
            return;
        }

        recoverExpiredLease();
        List<VideoStatusOutbox> claimed = claimService.claimBatch();
        for (VideoStatusOutbox outbox : claimed) {
            publishOne(outbox);
        }
    }

    private void publishOne(VideoStatusOutbox outbox) {
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
        } catch (Exception e) {
            String error = truncate(e.getMessage());
            if (outbox.getRetryCount() + 1 >= MAX_PUBLISH_RETRIES) {
                outboxMapper.markFailed(
                        outbox.getEventId(),
                        outbox.getLeaseToken(),
                        error
                );
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
            log.warn("视频统计 Outbox 发布失败，将重试, eventId={}",
                    outbox.getEventId(), e);
        }
    }

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