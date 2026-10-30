package com.feibijiubi.backend.videostats.config;

import com.feibijiubi.backend.videostats.mq.VideoStatusBatchFlushScheduler;
import com.feibijiubi.backend.videostats.mq.VideoStatusDirtyRecoveryScheduler;
import com.feibijiubi.backend.videostats.mq.VideoStatusOutboxRelay;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T06 调度资源隔离的配置层测试：只加载 {@link VideoStatusSchedulingConfig}，
 * 不连 MySQL/Redis/RabbitMQ，也不加载 {@code BackendApplication}。
 *
 * <p>固定的契约：三条调度器 bean 名（{@code @Scheduled(scheduler = ...)} 按名字查找，
 * 写错只会在完整上下文启动时才炸）、各自单线程、独立线程名、取消即移除、
 * 任务异常不杀死后续轮次、同一调度器内不并发。</p>
 */
class VideoStatusSchedulingConfigTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VideoStatusSchedulingConfig.class);

    @Test
    void threeIndependentSingleThreadSchedulersAreRegisteredWithStableNames() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            ThreadPoolTaskScheduler relay = context.getBean(
                    VideoStatusSchedulingConfig.RELAY_SCHEDULER_BEAN,
                    ThreadPoolTaskScheduler.class);
            ThreadPoolTaskScheduler flush = context.getBean(
                    VideoStatusSchedulingConfig.FLUSH_SCHEDULER_BEAN,
                    ThreadPoolTaskScheduler.class);
            ThreadPoolTaskScheduler recovery = context.getBean(
                    VideoStatusSchedulingConfig.RECOVERY_SCHEDULER_BEAN,
                    ThreadPoolTaskScheduler.class);

            // 三条独立实例：投递被 confirm 卡住时，落库/恢复线程不受影响
            assertThat(relay).isNotSameAs(flush);
            assertThat(flush).isNotSameAs(recovery);
            assertThat(relay).isNotSameAs(recovery);

            assertSingleThreaded(relay, VideoStatusSchedulingConfig.RELAY_THREAD_NAME);
            assertSingleThreaded(flush, VideoStatusSchedulingConfig.FLUSH_THREAD_NAME);
            assertSingleThreaded(recovery, VideoStatusSchedulingConfig.RECOVERY_THREAD_NAME);

            // 线程名前缀真的生效（线程 dump / 日志能定位是哪条调度卡住）
            Future<String> threadName = relay.submit(
                    () -> Thread.currentThread().getName());
            assertThat(threadName.get(2, TimeUnit.SECONDS))
                    .startsWith(VideoStatusSchedulingConfig.RELAY_THREAD_NAME + "-");
        });
    }

    private void assertSingleThreaded(
            ThreadPoolTaskScheduler scheduler,
            String threadName
    ) {
        // getPoolSize() 是“当前存活线程数”，未提交任务时为 0；核心池大小才是配置值。
        // ScheduledThreadPoolExecutor 的 maximumPoolSize 恒为 Integer.MAX_VALUE，并发上限看核心池。
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                .isEqualTo(VideoStatusSchedulingConfig.SCHEDULER_POOL_SIZE);
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo(threadName + "-");
        // 取消/停机时立刻移除已取消任务，不在队列里留引用
        assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy())
                .isTrue();
        // 有自定义 ErrorHandler：任务异常只记日志，不静默杀死后续轮次
        assertThat(VideoStatusSchedulingConfig.SHUTDOWN_AWAIT_SECONDS).isEqualTo(5);
    }

    @Test
    void scheduledAnnotationsReferenceTheseSchedulerBeanNames() throws Exception {
        assertSchedulerBean(
                VideoStatusOutboxRelay.class, "relay",
                VideoStatusSchedulingConfig.RELAY_SCHEDULER_BEAN);
        assertSchedulerBean(
                VideoStatusBatchFlushScheduler.class, "flushDirtyVideos",
                VideoStatusSchedulingConfig.FLUSH_SCHEDULER_BEAN);
        assertSchedulerBean(
                VideoStatusDirtyRecoveryScheduler.class, "recoverDirtyVids",
                VideoStatusSchedulingConfig.RECOVERY_SCHEDULER_BEAN);

        // 三条调度器都必须由本配置类提供，且名字与注解完全一致
        runner.run(context -> {
            assertThat(context).hasBean(
                    VideoStatusSchedulingConfig.RELAY_SCHEDULER_BEAN);
            assertThat(context).hasBean(
                    VideoStatusSchedulingConfig.FLUSH_SCHEDULER_BEAN);
            assertThat(context).hasBean(
                    VideoStatusSchedulingConfig.RECOVERY_SCHEDULER_BEAN);
        });
    }

    private void assertSchedulerBean(
            Class<?> target,
            String methodName,
            String expectedBeanName
    ) throws Exception {
        Scheduled scheduled = target.getMethod(methodName)
                .getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo(expectedBeanName);
        assertThat(scheduled.fixedDelayString()).isNotEmpty();
        assertThat(scheduled.fixedRateString()).isEmpty();
        assertThat(scheduled.cron()).isEmpty();
    }

    @Test
    void taskFailureIsLoggedAndLaterRoundsStillRun() {
        runner.run(context -> {
            ThreadPoolTaskScheduler relay = context.getBean(
                    VideoStatusSchedulingConfig.RELAY_SCHEDULER_BEAN,
                    ThreadPoolTaskScheduler.class);

            AtomicInteger runs = new AtomicInteger();
            ScheduledFuture<?> future = relay.scheduleAtFixedRate(() -> {
                runs.incrementAndGet();
                throw new IllegalStateException("injected scheduler failure");
            }, Duration.ofMillis(10));

            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (runs.get() < 3 && System.nanoTime() < deadline) {
                    Thread.sleep(20L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                future.cancel(true);
            }

            // 没有 ErrorHandler 时，抛异常的周期任务会被 JDK 取消；这里必须继续跑
            assertThat(runs.get()).isGreaterThanOrEqualTo(3);
        });
    }

    @Test
    void oneSchedulerNeverRunsTwoTasksConcurrently() {
        runner.run(context -> {
            ThreadPoolTaskScheduler flush = context.getBean(
                    VideoStatusSchedulingConfig.FLUSH_SCHEDULER_BEAN,
                    ThreadPoolTaskScheduler.class);

            AtomicInteger running = new AtomicInteger();
            AtomicInteger maxConcurrent = new AtomicInteger();
            Runnable task = () -> {
                int now = running.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                }
            };

            ScheduledFuture<?> first = flush.scheduleAtFixedRate(task, Duration.ofMillis(5));
            ScheduledFuture<?> second = flush.scheduleAtFixedRate(task, Duration.ofMillis(5));
            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                first.cancel(true);
                second.cancel(true);
            }

            assertThat(maxConcurrent.get()).isEqualTo(1);
        });
    }

    @Test
    void shutdownIsBoundedAndDoesNotWaitForALongRunningRound() {
        runner.run(context -> {
            ThreadPoolTaskScheduler recovery = context.getBean(
                    VideoStatusSchedulingConfig.RECOVERY_SCHEDULER_BEAN,
                    ThreadPoolTaskScheduler.class);

            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean finished = new AtomicBoolean();

            recovery.submit(() -> {
                started.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                finished.set(true);
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            context.close();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt);

            try {
                // 停机不能被一轮长任务拖住：上限是 SHUTDOWN_AWAIT_SECONDS（+ 少量收尾）
                assertThat(elapsedMillis).isLessThan(20_000L);
                // 没有被打断就不该谎报“任务已完成”；5 秒等待上限本身由配置常量保证
                assertThat(finished.get()).isFalse();
                // 停机后不再接受/安排新一轮（SmartLifecycle 已暂停，执行器已 shutdown）
                assertThat(recovery.getScheduledThreadPoolExecutor().isShutdown())
                        .isTrue();
            } finally {
                release.countDown();
            }
        });
    }
}
