package com.feibijiubi.backend.service.video.videostatus;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单应用实例内的同 vid 条带互斥器。
 *
 * <p>扩展为多应用实例前，必须替换为带续租和 token 解锁的分布式锁。</p>
 */
@Component
public class VideoStatusVidMutex {

    private static final int DEFAULT_STRIPE_COUNT = 256;

    private final ReentrantLock[] locks;

    public VideoStatusVidMutex() {
        this(DEFAULT_STRIPE_COUNT);
    }

    public VideoStatusVidMutex(int stripeCount) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount 必须大于 0");
        }
        locks = new ReentrantLock[stripeCount];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public <T> T withLock(Integer vid, Supplier<T> action) {
        Objects.requireNonNull(vid, "vid 不能为空");
        Objects.requireNonNull(action, "action 不能为空");

        ReentrantLock lock = locks[Math.floorMod(vid, locks.length)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
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
