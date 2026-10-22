package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.config.VideoStatusSchedulingConfig;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusStatus0CompensationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * T09 status0 安全补偿调度器。
 *
 * <p>独立单线程调度器、固定批次、单轮最多 {@code status0-compensation-batch-size} 条；
 * 进程内 keyset 游标只影响公平性，丢失最多从头再扫，不改变任何事件状态。
 * 默认关闭时只保留 {@link VideoStatusStatus0CompensationService#preview} 只读诊断。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatusStatus0CompensationScheduler {

    private final VideoStatusStatus0CompensationService compensationService;
    private final VideoStatusProperties properties;

    private final AtomicReference<Long> cursor = new AtomicReference<>();

    @Scheduled(
            fixedDelayString =
                    "${app.video-status.status0-compensation-fixed-delay-ms:5000}",
            scheduler = VideoStatusSchedulingConfig.COMPENSATION_SCHEDULER_BEAN
    )
    public void compensateStatus0() {
        if (!properties.isStatus0CompensationEnabled()) {
            return;
        }
        try {
            VideoStatusStatus0CompensationService.RoundResult result =
                    compensationService.runOnce(cursor.get());
            if (result.claimed() > 0 || result.overSafeWindow() > 0) {
                log.info("视频统计 status0 补偿单轮完成: claimed={} republished={} "
                                + "unprovenRepublished={} noProcessedEvidence={} "
                                + "released={} overSafeWindow={}",
                        result.claimed(), result.republished(),
                        result.unprovenRepublished(),
                        result.skippedNoProcessedEvidence(),
                        result.releasedOnFailure(), result.overSafeWindow());
            }
            cursor.set(result.nextCursor());
        } catch (RuntimeException e) {
            // 本轮失败不改游标，下一轮重试；异常类型足够诊断，不打印 payload。
            log.error("视频统计 status0 补偿单轮异常，保留游标等待下一轮: class={}",
                    e.getClass().getSimpleName());
        }
    }
}
