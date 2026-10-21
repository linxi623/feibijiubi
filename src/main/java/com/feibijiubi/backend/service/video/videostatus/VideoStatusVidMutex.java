package com.feibijiubi.backend.service.video.videostatus;

import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class VideoStatusVidMutex {

    private static final long WAIT_TIME_SECONDS = 10L;

    private final RedissonClient redissonClient;

    /**
     * T05 只读观测：允许为 null（纯构造测试/未装配指标的隔离配置）。
     * 任何指标调用都在 try/catch 内，绝不改变锁语义与业务异常。
     */
    @Autowired(required = false)
    private VideoStatusTelemetry telemetry;

    public <T> T withLock(Integer vid, Supplier<T> action) {
        Objects.requireNonNull(vid, "vid 不能为空");
        Objects.requireNonNull(action, "action 不能为空");

        RLock lock = redissonClient.getLock(RedisKeyUtils.lockKey(vid));
        boolean acquired = false;
        long lockWaitStartedAt = System.nanoTime();

        try {
            // 不传 leaseTime，使用 Redisson Watchdog 自动续租
            acquired = lock.tryLock(
                    WAIT_TIME_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!acquired) {
                recordLock(VideoStatusTelemetry.Outcome.TIMEOUT, lockWaitStartedAt);
                throw new RetryableMessageException(
                        "获取视频统计锁超时，vid=" + vid
                );
            }
            recordLock(VideoStatusTelemetry.Outcome.SUCCESS, lockWaitStartedAt);

            return action.get();
        } catch (InterruptedException e) {
            recordLock(VideoStatusTelemetry.Outcome.FAILURE, lockWaitStartedAt);
            Thread.currentThread().interrupt();
            throw new RetryableMessageException(
                    "等待视频统计锁时线程被中断，vid=" + vid,
                    e
            );
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void recordLock(
            VideoStatusTelemetry.Outcome outcome,
            long startedAt
    ) {
        VideoStatusTelemetry target = telemetry;
        if (target == null) {
            return;
        }
        try {
            target.record(
                    VideoStatusTelemetry.Operation.LOCK,
                    outcome,
                    System.nanoTime() - startedAt
            );
        } catch (RuntimeException ignored) {
            // 指标自身失败不得改变锁语义。
        }
    }

    public void withLock(Integer vid, Runnable action) {
        Objects.requireNonNull(action, "action 不能为空");

        withLock(vid, () -> {
            action.run();
            return null;
        });
    }
}