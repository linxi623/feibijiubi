package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.videostats.exception.RetryableMessageException;
import com.feibijiubi.backend.videostats.metrics.VideoStatusTelemetry;
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

    /**
     * 默认锁等待时间：消费/重建等“必须拿到锁”的路径沿用 T01 以来的 10 秒，
     * 语义不变（超时抛 {@link RetryableMessageException}）。
     */
    public static final long DEFAULT_WAIT_TIME_MILLIS = 10_000L;

    /**
     * 调度轮内的锁等待上限：批量落库是“扫一批 vid、能落就落”，
     * 一个视频被长时间占用时不该把整轮预算耗在等锁上，因此只等 100ms，
     * 没拿到就把 dirty 放回 Set，留给下一轮。
     */
    public static final long SCHEDULER_WAIT_TIME_MILLIS = 100L;

    private final RedissonClient redissonClient;

    /**
     * T05 只读观测：允许为 null（纯构造测试/未装配指标的隔离配置）。
     * 任何指标调用都在 try/catch 内，绝不改变锁语义与业务异常。
     */
    @Autowired(required = false)
    private VideoStatusTelemetry telemetry;

    public <T> T withLock(Integer vid, Supplier<T> action) {
        return withLock(vid, DEFAULT_WAIT_TIME_MILLIS, action);
    }

    /**
     * T06 新增的等待时间重载：只改“最多等多久”，锁本身、Watchdog 续租、
     * 超时异常类型与指标语义全部保持原样。
     *
     * @param waitTimeMillis 获取锁的最长等待时间（毫秒），必须大于 0
     */
    public <T> T withLock(Integer vid, long waitTimeMillis, Supplier<T> action) {
        Objects.requireNonNull(vid, "vid 不能为空");
        Objects.requireNonNull(action, "action 不能为空");
        if (waitTimeMillis <= 0) {
            throw new IllegalArgumentException(
                    "锁等待时间必须大于 0 毫秒，当前值：" + waitTimeMillis);
        }

        RLock lock = redissonClient.getLock(RedisKeyUtils.lockKey(vid));
        boolean acquired = false;
        long lockWaitStartedAt = System.nanoTime();

        try {
            // 不传 leaseTime，使用 Redisson Watchdog 自动续租
            acquired = lock.tryLock(
                    waitTimeMillis,
                    TimeUnit.MILLISECONDS
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
        withLock(vid, DEFAULT_WAIT_TIME_MILLIS, action);
    }

    public void withLock(Integer vid, long waitTimeMillis, Runnable action) {
        Objects.requireNonNull(action, "action 不能为空");

        withLock(vid, waitTimeMillis, () -> {
            action.run();
            return null;
        });
    }
}