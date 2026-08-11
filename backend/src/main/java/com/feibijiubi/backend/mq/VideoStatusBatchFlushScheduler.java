package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.common.VideoStatusFlushDataException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.service.video.videostatus.FlushResult;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusDeltaCleanupService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisHashOperations;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatusBatchFlushScheduler {

    private final VideoStatusProperties properties;
    private final RedisSetOperations redisSetOperations;
    private final RedisHashOperations redisHashOperations;
    private final VideoStatusRebuildService rebuildService;
    private final VideoStatusBatchFlushService batchFlushService;
    private final VideoStatusDeltaCleanupService cleanupService;
    private final VideoStatusConsumedEventMapper consumedEventMapper;
    private final VideoStatusVidMutex vidMutex;

    @Scheduled(
            fixedDelayString =
                    "${app.video-status.flush-fixed-delay-ms:500}"
    )
    public void flushDirtyVideos() {
        if (!properties.isAsyncEnabled()
                || !properties.isSchedulingEnabled()) {
            return;
        }

        Set<String> dirtyVids = redisSetOperations.pop(
                RedisKeyUtils.dirtyVideo(),
                properties.getFlushDirtyBatchSize()
        );
        for (String rawVid : dirtyVids) {
            Integer vid = parseVid(rawVid);
            if (vid == null) {
                log.error("dirty Set 包含非法 vid，已忽略: {}", rawVid);
                continue;
            }
            flushOne(vid);
        }
    }

    private void flushOne(Integer vid) {
        try {
            vidMutex.withLock(vid, () -> flushLocked(vid));
        } catch (VideoStatusFlushDataException e) {
            handleDataError(e);
        } catch (DataAccessException
                 | RetryableMessageException
                 | RedisOperationException e) {
            requeueDirty(vid);
            log.warn("视频统计批量刷库暂时失败，将重试: vid={}", vid, e);
        } catch (Exception e) {
            requeueDirty(vid);
            log.error("视频统计批量刷库发生未知异常，将保留 dirty: vid={}", vid, e);
        }
    }

    private void flushLocked(Integer vid) {
        String generation = redisHashOperations.get(
                RedisKeyUtils.videoStatus(vid),
                "generation"
        );
        if (generation == null || generation.isBlank()) {
            rebuildService.ensureInitialized(vid);
            generation = redisHashOperations.get(
                    RedisKeyUtils.videoStatus(vid),
                    "generation"
            );
        }
        if (generation == null || generation.isBlank()) {
            throw new RetryableMessageException(
                    "Redis generation 缺失，vid=" + vid
            );
        }

        FlushResult result = batchFlushService.flushOneVideo(
                vid,
                properties.getFlushEventBatchSize(),
                generation
        );
        if (!result.empty()) {
            try {
                cleanupService.cleanup(result.batchId());
            } catch (Exception cleanupError) {
                log.warn(
                        "MySQL 刷库已提交，但 Redis delta 清理失败，等待 cleanup 恢复: batchId={}",
                        result.batchId(),
                        cleanupError
                );
            }
        }
        refreshDirtyMembership(vid);
    }

    private void handleDataError(VideoStatusFlushDataException error) {
        Integer vid = error.getVid();
        try {
            batchFlushService.markRepairRequired(
                    error.getConsumedEventIds(),
                    error.getErrorSummary()
            );
            refreshDirtyMembership(vid);
            log.error(
                    "视频统计刷库数据异常，事件已转人工修复: vid={}, ids={}",
                    vid,
                    error.getConsumedEventIds(),
                    error
            );
        } catch (Exception markError) {
            requeueDirty(vid);
            log.error(
                    "视频统计刷库数据异常且转人工修复失败: vid={}",
                    vid,
                    markError
            );
        }
    }

    private void refreshDirtyMembership(Integer vid) {
        if (consumedEventMapper.countPendingByVid(vid) > 0) {
            requeueDirty(vid);
        } else {
            redisSetOperations.remove(
                    RedisKeyUtils.dirtyVideo(),
                    String.valueOf(vid)
            );
        }
    }

    private void requeueDirty(Integer vid) {
        try {
            redisSetOperations.add(
                    RedisKeyUtils.dirtyVideo(),
                    String.valueOf(vid)
            );
        } catch (Exception e) {
            log.error("重新加入 dirty Set 失败: vid={}", vid, e);
        }
    }

    private Integer parseVid(String rawVid) {
        try {
            int vid = Integer.parseInt(rawVid);
            return vid > 0 ? vid : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
