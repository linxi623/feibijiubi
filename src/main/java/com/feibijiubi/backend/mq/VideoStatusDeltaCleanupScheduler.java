package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusDeltaCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatusDeltaCleanupScheduler {

    private final VideoStatusProperties properties;
    private final VideoStatusDeltaCleanupService cleanupService;

    @Scheduled(
            fixedDelayString =
                    "${app.video-status.cleanup-fixed-delay-ms:1000}"
    )
    public void retryPendingCleanup() {
        if (!properties.isAsyncEnabled()
                || !properties.isSchedulingEnabled()) {
            return;
        }
        try {
            cleanupService.retryPending(properties.getCleanupBatchSize());
        } catch (Exception e) {
            log.warn("扫描 Redis delta 待清理批次失败", e);
        }
    }
}
