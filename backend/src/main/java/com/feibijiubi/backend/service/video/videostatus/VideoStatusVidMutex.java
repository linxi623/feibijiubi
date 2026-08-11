package com.feibijiubi.backend.service.video.videostatus;

import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class VideoStatusVidMutex {

    private static final long WAIT_TIME_SECONDS = 10L;

    private final RedissonClient redissonClient;

    public <T> T withLock(Integer vid, Supplier<T> action) {
        Objects.requireNonNull(vid, "vid 不能为空");
        Objects.requireNonNull(action, "action 不能为空");

        RLock lock = redissonClient.getLock(RedisKeyUtils.lockKey(vid));
        boolean acquired = false;

        try {
            // 不传 leaseTime，使用 Redisson Watchdog 自动续租
            acquired = lock.tryLock(
                    WAIT_TIME_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!acquired) {
                throw new RetryableMessageException(
                        "获取视频统计锁超时，vid=" + vid
                );
            }

            return action.get();
        } catch (InterruptedException e) {
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

    public void withLock(Integer vid, Runnable action) {
        Objects.requireNonNull(action, "action 不能为空");

        withLock(vid, () -> {
            action.run();
            return null;
        });
    }
}