package com.feibijiubi.backend.service.impl.video.videostatus;

import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusFlushBatch;
import com.feibijiubi.backend.enums.VideoStatusFlushCleanupStatus;
import com.feibijiubi.backend.mapper.VideoStatusFlushBatchMapper;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusDeltaCleanupService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusService;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoStatusDeltaCleanupServiceImpl
        implements VideoStatusDeltaCleanupService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final VideoStatusFlushBatchMapper flushBatchMapper;
    private final RedisUtils redisUtils;
    private final VideoStatusProperties properties;
    private final ObjectProvider<VideoStatusDeltaCleanupService> selfProvider;

    @Resource(name = "videoStatusDeltaSubtractScript")
    private DefaultRedisScript<String> deltaSubtractScript;

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void cleanup(String batchId) {
        VideoStatusFlushBatch batch =
                flushBatchMapper.selectByBatchIdForUpdate(batchId);
        if (batch == null) {
            throw new RetryableMessageException(
                    "未找到 Redis delta 清理批次，batchId=" + batchId
            );
        }

        Integer status = batch.getCleanupStatus();
        if (Objects.equals(status,
                VideoStatusFlushCleanupStatus.CLEANED.getCode())
                || Objects.equals(status,
                VideoStatusFlushCleanupStatus.SKIPPED_GENERATION_CHANGED
                        .getCode())) {
            return;
        }
        if (!Objects.equals(status,
                VideoStatusFlushCleanupStatus.PENDING.getCode())) {
            log.warn(
                    "Redis delta 清理批次不再是 PENDING，跳过: batchId={}, status={}",
                    batchId,
                    status
            );
            return;
        }

        String cleanupKey = RedisKeyUtils.flushVideo(batchId);
        String scriptResult = redisUtils.executeScript(
                deltaSubtractScript,
                List.of(
                        RedisKeyUtils.videoStatus(batch.getVid()),
                        RedisKeyUtils.videoStatusDelta(batch.getVid()),
                        RedisKeyUtils.dirtyVideo(),
                        cleanupKey
                ),
                String.valueOf(batch.getVid()),
                batch.getRedisGeneration(),
                String.valueOf(batch.getPlayDelta()),
                String.valueOf(batch.getLikeDelta()),
                String.valueOf(batch.getUnlikeDelta()),
                String.valueOf(batch.getCommentDelta()),
                String.valueOf(batch.getCoinDelta()),
                String.valueOf(batch.getShareDelta()),
                String.valueOf(batch.getCollectDelta()),
                String.valueOf(batch.getDanmuDelta())
        );

        VideoStatusService.DeltaCleanupResult result =
                parseResult(scriptResult, batch);
        if (result == null) {
            return;
        }
        switch (result) {
            case EMPTY, REMAINING, DUPLICATE_CLEANUP -> {
                requireSingleUpdate(
                        flushBatchMapper.markCleaned(batchId),
                        "标记 Redis delta 清理完成失败"
                );
                deleteCleanupMarkerAfterCommit(cleanupKey, batchId);
            }
            case GENERATION_CHANGED -> requireSingleUpdate(
                    flushBatchMapper.markGenerationSkipped(batchId),
                    "标记 generation 已变化失败"
            );
            case NEEDS_REBUILD, INVALID_ARGUMENT ->
                    recordKnownFailure(batch, result.name());
        }
    }

    @Override
    public int retryPending(int limit) {
        if (limit <= 0) {
            return 0;
        }
        List<VideoStatusFlushBatch> pending =
                flushBatchMapper.selectCleanupPending(limit);
        int succeeded = 0;
        for (VideoStatusFlushBatch batch : pending) {
            try {
                selfProvider.getObject().cleanup(batch.getBatchId());
                succeeded++;
            } catch (Exception e) {
                log.warn(
                        "重试 Redis delta 清理失败: batchId={}",
                        batch.getBatchId(),
                        e
                );
            }
        }
        return succeeded;
    }

    private VideoStatusService.DeltaCleanupResult parseResult(
            String scriptResult,
            VideoStatusFlushBatch batch
    ) {
        if (scriptResult == null) {
            recordKnownFailure(batch, "Redis 清理脚本无返回值");
            return null;
        }
        try {
            return VideoStatusService.DeltaCleanupResult.valueOf(scriptResult);
        } catch (IllegalArgumentException e) {
            recordKnownFailure(
                    batch,
                    "未知 Redis 清理脚本结果: " + scriptResult
            );
            return null;
        }
    }

    private void recordKnownFailure(
            VideoStatusFlushBatch batch,
            String reason
    ) {
        int nextAttempt = (batch.getCleanupRetryCount() == null
                ? 0
                : batch.getCleanupRetryCount()) + 1;
        String error = truncate(reason);
        int updated;
        if (nextAttempt >= properties.getCleanupMaxAttempts()) {
            updated = flushBatchMapper.markCleanupRepairRequired(
                    batch.getBatchId(),
                    error
            );
        } else {
            updated = flushBatchMapper.recordCleanupFailure(
                    batch.getBatchId(),
                    error
            );
        }
        requireSingleUpdate(updated, "记录 Redis delta 清理失败状态失败");
    }

    private void deleteCleanupMarkerAfterCommit(
            String cleanupKey,
            String batchId
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("cleanup 必须在 Spring 事务代理中执行");
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            redisUtils.delete(cleanupKey);
                        } catch (Exception e) {
                            log.warn(
                                    "删除 flush-cleaned 幂等 Key 失败，保留该 Key 是安全的: batchId={}",
                                    batchId,
                                    e
                            );
                        }
                    }
                }
        );
    }

    private void requireSingleUpdate(int updated, String message) {
        if (updated != 1) {
            throw new RetryableMessageException(message);
        }
    }

    private String truncate(String message) {
        String value = message == null || message.isBlank()
                ? "unknown cleanup error"
                : message;
        return value.length() <= MAX_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_LENGTH);
    }
}
