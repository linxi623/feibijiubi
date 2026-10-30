package com.feibijiubi.backend.videostats.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T06 验收：调度资源隔离（线程名、线程归属、阻塞/异常隔离、优雅停机）。
 *
 * <p>本类只验证“调度资源/线程/停机”这一层，不验证批量预算、领取行数、幂等与门闩；
 * 后面那些属于落库/投递逻辑，本类刻意不碰数据库、Redis、RabbitMQ。
 * 单轮预算（批量、最长处理时间）与两实例并发落库属于 T06 实现侧的其它验收项，见报告缺口。</p>
 *
 * <p>为什么用真实 {@code @EnableScheduling} 轻量上下文而不是 mock 调度器：</p>
 * <ul>
 *   <li>只有真实的 {@link org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor}
 *       链路才会按 {@code @Scheduled(scheduler = "...")} 去解析生产 Bean 名，
 *       mock 一个 {@code TaskScheduler} 就等于自己把配置“抄”一遍，测不出生产配置；</li>
 *   <li>线程名前缀、线程数、优雅停机都是生产 {@code SchedulingConfig} 的属性，
 *       必须从真实 Bean 上读；</li>
 *   <li>{@link ApplicationContextRunner} 不加载 {@code BackendApplication}、
 *       不导入自动配置，因此不会创建 DataSource / Redis / RabbitMQ，
 *       也不会启动消费者，符合“不访问任何非测试资源”的约束。</li>
 * </ul>
 *
 * <p>与 {@code VideoStatusDirtySchedulingTests} 的分工：那个类测落库调度方法的
 * dirty 语义（SPOP/SREM、退避、修复分类）；本类测“三个角色是否各自持有独立线程、
 * 一个角色被长时间阻塞时其它角色是否还能跑、上下文关闭后线程是否干净退出”。</p>
 *
 * <p>依赖：本类导入生产 {@code com.feibijiubi.backend.config.SchedulingConfig}
 * （T06 实现），因此必须与该实现同分支合并后才能编译/运行。</p>
 *
 * <p>验收口径的落点：发布任务用 {@link CountDownLatch} 真实阻塞至少 30 秒
 * （不是抛异常或短暂 sleep），阻塞期间落库/恢复任务必须在 10 秒测试预算内继续执行；
 * 释放后线程正常结束，上下文关闭后不残留调度线程。</p>
 */
class VideoStatusSchedulingIsolationTests {

    /** T06 约定的三个独立调度资源，名字固定，便于线程 dump 与运维核对。 */
    private static final String RELAY_SCHEDULER = "videoStatusRelayScheduler";
    private static final String FLUSH_SCHEDULER = "videoStatusFlushScheduler";
    private static final String RECOVERY_SCHEDULER = "videoStatusRecoveryScheduler";

    /** T06 验收口径：把发布 confirm 人为阻塞 30 秒。 */
    private static final long BLOCKED_PUBLISH_MILLIS = TimeUnit.SECONDS.toMillis(30);

    /** T06 验收口径：阻塞期间落库/恢复仍要在 10 秒测试预算内落库并保持心跳。 */
    private static final long OTHER_ROLE_BUDGET_MILLIS = TimeUnit.SECONDS.toMillis(10);

    /** 任务自身等待上限：比 30 秒再留冗余，避免测试失败时把 JVM 永久挂住。 */
    private static final long RELAY_WAIT_LIMIT_MILLIS = BLOCKED_PUBLISH_MILLIS + 15_000L;

    private static ApplicationContextRunner runnerFor(Class<?> configuration) {
        return new ApplicationContextRunner().withUserConfiguration(configuration);
    }

    /**
     * 真实的轻量上下文：{@code @EnableScheduling} + 生产 {@code SchedulingConfig}
     * + 三个用 {@code @Scheduled(scheduler = ...)} 绑定的探针任务。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(VideoStatusSchedulingConfig.class)
    static class BlockingSchedulingConfiguration {

        /**
         * 生产调度配置只负责线程资源，本身不依赖属性；这里给一个默认值实例，
         * 是为了让“如果 T06 实现改成读取 app.video-status.* 预算”时上下文仍然成立。
         * {@code @Primary} 保证即使实现自己也注册了同类型 Bean 也不会出现注入歧义。
         */
        @Bean
        @Primary
        VideoStatusProperties videoStatusProperties() {
            return new VideoStatusProperties();
        }

        @Bean
        SchedulingProbe schedulingProbe() {
            return new SchedulingProbe(RelayBehaviour.BLOCK);
        }
    }

    /** 发布任务从第一轮就抛异常：验证调度任务的异常隔离。 */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(VideoStatusSchedulingConfig.class)
    static class ThrowingRelayConfiguration {

        @Bean
        @Primary
        VideoStatusProperties videoStatusProperties() {
            return new VideoStatusProperties();
        }

        @Bean
        SchedulingProbe schedulingProbe() {
            return new SchedulingProbe(RelayBehaviour.THROW);
        }
    }

    /** 发布任务的两种可测行为：长时间阻塞 / 直接抛异常。 */
    enum RelayBehaviour {
        BLOCK,
        THROW
    }

    // ------------------------------------------------------------------ 用例

    @Test
    void eachRoleRunsOnItsOwnNamedSchedulerAndThreadsShutDownCleanly() {
        runnerFor(BlockingSchedulingConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));
            assertNoInfrastructure(context);

            SchedulingProbe probe = context.getBean(SchedulingProbe.class);
            try {
                assertTrue(probe.relayEntered.await(OTHER_ROLE_BUDGET_MILLIS, TimeUnit.MILLISECONDS),
                        "发布任务应在 10 秒内被调度");
            } finally {
                // 本用例只关心线程归属，立刻释放阻塞，让三个任务可以重复执行。
                probe.relayReleased.countDown();
            }
            awaitRuns(probe.relayRuns, 3, 5_000, "relay");
            awaitRuns(probe.flushRuns, 3, 5_000, "flush");
            awaitRuns(probe.recoveryRuns, 3, 5_000, "recovery");

            ThreadPoolTaskScheduler relay = scheduler(context, RELAY_SCHEDULER);
            ThreadPoolTaskScheduler flush = scheduler(context, FLUSH_SCHEDULER);
            ThreadPoolTaskScheduler recovery = scheduler(context, RECOVERY_SCHEDULER);

            String relayPrefix = threadNamePrefix(relay, RELAY_SCHEDULER);
            String flushPrefix = threadNamePrefix(flush, FLUSH_SCHEDULER);
            String recoveryPrefix = threadNamePrefix(recovery, RECOVERY_SCHEDULER);

            assertEquals(3,
                    Stream.of(relayPrefix, flushPrefix, recoveryPrefix).distinct().count(),
                    "三个角色的线程名前缀必须互不相同，否则线程 dump 无法区分角色："
                            + relayPrefix + " / " + flushPrefix + " / " + recoveryPrefix);

            String relayThread = probe.relayThread.get();
            String flushThread = probe.flushThread.get();
            String recoveryThread = probe.recoveryThread.get();
            assertNotNull(relayThread);
            assertNotNull(flushThread);
            assertNotNull(recoveryThread);

            // 真正执行任务的线程必须带各自配置的前缀（不是 JVM 默认 pool-N）。
            assertTrue(relayThread.startsWith(relayPrefix),
                    "relay 线程名必须以配置的前缀开头：" + relayThread + " vs " + relayPrefix);
            assertTrue(flushThread.startsWith(flushPrefix),
                    "flush 线程名必须以配置的前缀开头：" + flushThread + " vs " + flushPrefix);
            assertTrue(recoveryThread.startsWith(recoveryPrefix),
                    "recovery 线程名必须以配置的前缀开头：" + recoveryThread + " vs " + recoveryPrefix);

            // 线程名要能追溯到角色信息，否则运维看线程 dump 找不到对应调度器。
            assertThreadNameMentionsRole(relayThread, RELAY_SCHEDULER, "relay", "outbox", "publish");
            assertThreadNameMentionsRole(flushThread, FLUSH_SCHEDULER, "flush");
            assertThreadNameMentionsRole(recoveryThread, RECOVERY_SCHEDULER,
                    "recovery", "rebuild", "dirty");

            // 三个角色绝不能共用同一条调度线程（共用就退化成单池，会互相饿死）。
            assertEquals(3,
                    Stream.of(relayThread, flushThread, recoveryThread).distinct().count(),
                    "三个角色不能共用调度线程：" + relayThread + " / " + flushThread + " / " + recoveryThread);

            // 同一任务在 fixedDelay 下不得重入（重叠执行会让单轮预算失效）。
            assertEquals(0, probe.overlappedTicks.get(), "同一调度任务不得重叠执行");

            context.close();
            assertSchedulerStopped(relay, RELAY_SCHEDULER);
            assertSchedulerStopped(flush, FLUSH_SCHEDULER);
            assertSchedulerStopped(recovery, RECOVERY_SCHEDULER);
        });
    }

    @Test
    void relayTickFailureIsIsolatedFromFlushAndRecoverySchedulers() {
        runnerFor(ThrowingRelayConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));

            SchedulingProbe probe = context.getBean(SchedulingProbe.class);
            // 探针从第一轮就抛异常：模拟一次发布/confirm 直接失败，而不是阻塞。
            awaitRuns(probe.relayRuns, 3, 5_000, "relay");
            // 发布任务反复抛异常时，落库与恢复调度必须继续推进；Spring 的
            // ReschedulingRunnable 会重排下一次 fixedDelay，不能被异常打断。
            awaitRuns(probe.flushRuns, 3, 5_000, "flush");
            awaitRuns(probe.recoveryRuns, 3, 5_000, "recovery");

            assertEquals(0, probe.overlappedTicks.get(), "任务异常不得变成重叠执行");
        });
    }

    @Test
    void blockedRelayPublishKeepsFlushAndRecoveryAliveAndReleasesCleanly() {
        runnerFor(BlockingSchedulingConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));

            SchedulingProbe probe = context.getBean(SchedulingProbe.class);
            try {
                assertTrue(probe.relayEntered.await(OTHER_ROLE_BUDGET_MILLIS, TimeUnit.MILLISECONDS),
                        "发布任务应在 10 秒内进入阻塞");
                long blockedAt = probe.relayBlockStartedAtNanos;

                // 1) 发布任务被真实阻塞期间，落库/恢复必须在 10 秒预算内继续推进。
                int flushBaseline = probe.flushRuns.get();
                int recoveryBaseline = probe.recoveryRuns.get();
                awaitRuns(probe.flushRuns, flushBaseline + 2,
                        OTHER_ROLE_BUDGET_MILLIS, "flush");
                awaitRuns(probe.recoveryRuns, recoveryBaseline + 2,
                        OTHER_ROLE_BUDGET_MILLIS, "recovery");
                long observedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - blockedAt);
                assertTrue(observedMillis <= OTHER_ROLE_BUDGET_MILLIS,
                        "落库/恢复必须在 10 秒测试预算内执行，实际用时 " + observedMillis + " 毫秒");
                assertEquals(1, probe.relayExited.getCount(),
                        "落库/恢复执行时发布任务必须仍处于阻塞状态");
                assertEquals(1, probe.relayRuns.get(),
                        "单线程调度下阻塞期间不得出现同一任务的重入执行");

                // 2) 发布任务必须真的被阻塞满 30 秒（不是只抛一次异常或立刻返回）。
                long remainingMillis = BLOCKED_PUBLISH_MILLIS
                        - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - blockedAt);
                if (remainingMillis > 0) {
                    Thread.sleep(remainingMillis);
                }
                assertFalse(probe.relayAwaitTimedOut,
                        "发布任务等待超时，说明阻塞/释放语义不符合预期");
                assertEquals(1, probe.relayExited.getCount(),
                        "发布任务必须保持阻塞至少 " + BLOCKED_PUBLISH_MILLIS + " 毫秒");
                assertEquals(1, probe.relayRuns.get(),
                        "30 秒阻塞期间发布任务只允许被调度一次");

                // 3) 阻塞满 30 秒后落库/恢复仍在持续心跳（不是只跑一次就停）。
                assertTrue(probe.flushRuns.get() >= flushBaseline + 2,
                        "阻塞期间落库调度必须持续执行，实际次数 " + probe.flushRuns.get());
                assertTrue(probe.recoveryRuns.get() >= recoveryBaseline + 2,
                        "阻塞期间恢复调度必须持续执行，实际次数 " + probe.recoveryRuns.get());
            } finally {
                // 无论如何都要释放，避免失败路径把测试进程挂住 30 秒以上。
                probe.relayReleased.countDown();
            }

            assertTrue(probe.relayExited.await(OTHER_ROLE_BUDGET_MILLIS, TimeUnit.MILLISECONDS),
                    "释放后发布任务应正常返回");
            assertEquals(0, probe.overlappedTicks.get(), "调度任务不得重叠执行");

            ThreadPoolTaskScheduler relay = scheduler(context, RELAY_SCHEDULER);
            ThreadPoolTaskScheduler flush = scheduler(context, FLUSH_SCHEDULER);
            ThreadPoolTaskScheduler recovery = scheduler(context, RECOVERY_SCHEDULER);

            context.close();
            // 4) 被阻塞过之后，三个调度器仍然要正常停机、不残留线程。
            assertSchedulerStopped(relay, RELAY_SCHEDULER);
            assertSchedulerStopped(flush, FLUSH_SCHEDULER);
            assertSchedulerStopped(recovery, RECOVERY_SCHEDULER);
        });
    }

    // ------------------------------------------------------------------ 探针

    /**
     * 三个“业务任务”本身：只做计数与阻塞/抛异常，不碰数据库、Redis、MQ。
     * 它们通过 {@code @Scheduled(scheduler = "...")} 绑定到生产调度 Bean，
     * 因此线程名、线程数、停机行为完全由生产 {@code SchedulingConfig} 决定。
     */
    static class SchedulingProbe {

        private final RelayBehaviour relayBehaviour;

        SchedulingProbe(RelayBehaviour relayBehaviour) {
            this.relayBehaviour = relayBehaviour;
        }

        final CountDownLatch relayEntered = new CountDownLatch(1);
        final CountDownLatch relayReleased = new CountDownLatch(1);
        final CountDownLatch relayExited = new CountDownLatch(1);

        final AtomicInteger relayRuns = new AtomicInteger();
        final AtomicInteger flushRuns = new AtomicInteger();
        final AtomicInteger recoveryRuns = new AtomicInteger();

        final AtomicInteger relayActive = new AtomicInteger();
        final AtomicInteger flushActive = new AtomicInteger();
        final AtomicInteger recoveryActive = new AtomicInteger();
        final AtomicInteger overlappedTicks = new AtomicInteger();

        final AtomicReference<String> relayThread = new AtomicReference<>();
        final AtomicReference<String> flushThread = new AtomicReference<>();
        final AtomicReference<String> recoveryThread = new AtomicReference<>();

        volatile long relayBlockStartedAtNanos;
        volatile boolean relayAwaitTimedOut;

        @Scheduled(fixedDelay = 200, scheduler = RELAY_SCHEDULER)
        void relayTick() {
            int concurrent = relayActive.incrementAndGet();
            if (concurrent > 1) {
                overlappedTicks.incrementAndGet();
            }
            try {
                relayRuns.incrementAndGet();
                relayThread.compareAndSet(null, Thread.currentThread().getName());
                if (relayBehaviour == RelayBehaviour.THROW) {
                    relayEntered.countDown();
                    throw new IllegalStateException("模拟发布 confirm 失败：调度异常必须被隔离");
                }
                relayBlockStartedAtNanos = System.nanoTime();
                relayEntered.countDown();
                // 真实阻塞：只有测试主线程在确认落库/恢复已执行、且阻塞满 30 秒后才释放。
                if (!relayReleased.await(RELAY_WAIT_LIMIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    relayAwaitTimedOut = true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                relayActive.decrementAndGet();
                relayExited.countDown();
            }
        }

        @Scheduled(fixedDelay = 100, scheduler = FLUSH_SCHEDULER)
        void flushTick() {
            int concurrent = flushActive.incrementAndGet();
            if (concurrent > 1) {
                overlappedTicks.incrementAndGet();
            }
            try {
                flushThread.compareAndSet(null, Thread.currentThread().getName());
                flushRuns.incrementAndGet();
            } finally {
                flushActive.decrementAndGet();
            }
        }

        @Scheduled(fixedDelay = 100, scheduler = RECOVERY_SCHEDULER)
        void recoveryTick() {
            int concurrent = recoveryActive.incrementAndGet();
            if (concurrent > 1) {
                overlappedTicks.incrementAndGet();
            }
            try {
                recoveryThread.compareAndSet(null, Thread.currentThread().getName());
                recoveryRuns.incrementAndGet();
            } finally {
                recoveryActive.decrementAndGet();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ThreadPoolTaskScheduler scheduler(
            AssertableApplicationContext context,
            String beanName
    ) {
        ThreadPoolTaskScheduler scheduler =
                context.getBean(beanName, ThreadPoolTaskScheduler.class);
        assertNotNull(scheduler, beanName + " 必须由生产 SchedulingConfig 注册");
        return scheduler;
    }

    /**
     * 读取生产配置的线程名前缀。未显式 setThreadNamePrefix 时 Spring 用 Bean 名 + "-"，
     * 这里按同一规则回退，避免把“实现选择默认值”误判成错误。
     */
    private static String threadNamePrefix(
            ThreadPoolTaskScheduler scheduler,
            String beanName
    ) {
        String configured = scheduler.getThreadNamePrefix();
        String prefix = (configured == null || configured.isBlank())
                ? beanName + "-"
                : configured;
        assertFalse(prefix.isBlank(), beanName + " 必须能给出可识别的线程名前缀");
        return prefix;
    }

    private static void assertThreadNameMentionsRole(
            String threadName,
            String beanName,
            String... roleWords
    ) {
        String lower = threadName.toLowerCase(Locale.ROOT);
        for (String roleWord : roleWords) {
            if (lower.contains(roleWord)) {
                return;
            }
        }
        fail("线程名应能追溯回角色 " + beanName + "（可接受关键词 "
                + String.join("/", roleWords) + "），实际：" + threadName);
    }

    private static void assertSchedulerStopped(
            ThreadPoolTaskScheduler scheduler,
            String beanName
    ) throws InterruptedException {
        String prefix = threadNamePrefix(scheduler, beanName);
        assertTrue(scheduler.getScheduledThreadPoolExecutor()
                        .awaitTermination(OTHER_ROLE_BUDGET_MILLIS, TimeUnit.MILLISECONDS),
                beanName + " 必须优雅停机");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hasLiveThreadWithPrefix(prefix) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(hasLiveThreadWithPrefix(prefix),
                beanName + " 停机后不应残留 " + prefix + "* 线程");
    }

    private static boolean hasLiveThreadWithPrefix(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith(prefix));
    }

    private static void awaitRuns(
            AtomicInteger counter,
            int target,
            long timeoutMillis,
            String label
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (counter.get() < target && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(counter.get() >= target,
                label + " 调度在 " + timeoutMillis + " 毫秒内至少应执行 "
                        + target + " 次，实际 " + counter.get());
    }

    /** 证明这个轻量上下文没有拉进任何数据库 / Redis / RabbitMQ 基础设施。 */
    private static void assertNoInfrastructure(AssertableApplicationContext context) {
        assertEquals(0, context.getBeanNamesForType(DataSource.class).length,
                "不得创建 DataSource");
        assertEquals(0, context.getBeanNamesForType(JdbcTemplate.class).length,
                "不得创建 JdbcTemplate");
        assertEquals(0, context.getBeanNamesForType(RedisConnectionFactory.class).length,
                "不得创建 Redis 连接工厂");
        assertEquals(0, context.getBeanNamesForType(ConnectionFactory.class).length,
                "不得创建 RabbitMQ 连接工厂");
        assertEquals(0, context.getBeanNamesForType(RabbitListenerContainerFactory.class).length,
                "不得创建监听容器（也就不会消费消息）");
    }

    private static String failureMessage(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        if (failure == null) {
            return "";
        }
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(current.getMessage())
                    .append('\n');
        }
        return messages.toString();
    }
}
