package com.feibijiubi.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * T05 观测专用线程资源。
 *
 * <p>为什么单独一个<b>单线程</b>调度器：</p>
 * <ul>
 *   <li>发布/落库/恢复的调度器可能被 confirm、vid 锁或大事务阻塞；
 *       如果指标采样共用同一条线程，链路卡住时监控也一起卡死，
 *       恰好失去定位能力；</li>
 *   <li>单线程保证两次采样不会重叠，采样本身不会堆积成新的负载；</li>
 *   <li>daemon 线程 + 固定名字：不阻止 JVM 退出，线程 dump 一眼可辨。</li>
 * </ul>
 *
 * <p>本类不采集任何指标：只有线程池定义。采样逻辑在
 * {@code metrics/VideoStatusBacklogSampler}。</p>
 */
@Configuration
public class VideoStatusMetricsConfig {

    public static final String METRICS_SCHEDULER_BEAN = "videoStatusMetricsScheduler";
    public static final String METRICS_THREAD_NAME = "video-status-metrics-sampler";

    @Bean(name = METRICS_SCHEDULER_BEAN, destroyMethod = "shutdownNow")
    public ScheduledExecutorService videoStatusMetricsScheduler() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(
                        runnable,
                        METRICS_THREAD_NAME + "-" + sequence.incrementAndGet()
                );
                // 监控线程不持有 JVM 生命周期
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }
}
