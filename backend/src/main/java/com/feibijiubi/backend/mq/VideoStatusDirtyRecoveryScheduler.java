package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Scheduled(
            fixedDelayString =
                    "${app.video-status.flush-recovery-fixed-delay-ms:5000}"
    )
    public void recoverDirtyVids() {
        if (!properties.isAsyncEnabled()
                || !properties.isSchedulingEnabled()) {
            return;
        }

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
}
