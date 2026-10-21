package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatusDirtyRecoveryScheduler {

    private final VideoStatusProperties properties;
    private final VideoStatusConsumedEventMapper consumedEventMapper;
    private final RedisSetOperations redisSetOperations;

    /**
     * T05 只读观测：允许为 null（纯构造测试/未装配指标的隔离配置）。
     * 心跳只证明“恢复调度还在跑”，不参与 dirty 重加逻辑。
     */
    @Autowired(required = false)
    private VideoStatusTelemetry telemetry;

    @Scheduled(
            fixedDelayString =
                    "${app.video-status.flush-recovery-fixed-delay-ms:5000}"
    )
    public void recoverDirtyVids() {
        if (!properties.isAsyncEnabled()
                || !properties.isSchedulingEnabled()) {
            return;
        }

        heartbeat(VideoStatusTelemetry.Operation.RECOVERY_TICK);
        List<Integer> vids = consumedEventMapper.selectPendingVids(
                properties.getFlushDirtyBatchSize()
        );
        for (Integer vid : vids) {
            try {
                redisSetOperations.add(
                        RedisKeyUtils.dirtyVideo(),
                        String.valueOf(vid)
                );
            } catch (Exception e) {
                log.warn("恢复 dirty vid 失败: vid={}", vid, e);
            }
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
            // 心跳失败不影响恢复调度。
        }
    }
}
