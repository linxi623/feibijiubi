package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.config.VideoStatusSchedulingConfig;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mq.VideoStatusBatchFlushScheduler;
import com.feibijiubi.backend.mq.VideoStatusDirtyRecoveryScheduler;
import com.feibijiubi.backend.mq.VideoStatusEventPublisher;
import com.feibijiubi.backend.mq.VideoStatusOutboxRelay;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusBatchFlushServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusConsumptionService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusEventFingerprintService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * T06 验收：<b>真实业务组件</b>下的调度隔离与“同一 vid 只计一次”。
 *
 * <p>与 {@code com.feibijiubi.backend.config.VideoStatusSchedulingIsolationTests} 的分工：
 * 那个类只验证线程资源/停机（不连数据库、Redis、RabbitMQ）；本类在隔离的真实资源上验证
 * “投递被真实阻塞 30 秒时，落库与恢复是否仍然跑真实业务代码”，以及“两个独立落库调度器
 * 并发处理同一个 vid 时是否只落一次库”。</p>
 *
 * <p>为什么不用 {@code @EnableScheduling}：本上下文里已经有生产的
 * {@link VideoStatusOutboxRelay}、{@link VideoStatusBatchFlushScheduler} 与
 * {@link VideoStatusDirtyRecoveryScheduler}。一旦开启 {@code @EnableScheduling}，
 * 这些 {@code @Scheduled} 方法会在测试期间对<b>整个</b>隔离库/Redis 持续运行，
 * 既有历史 dirty/Outbox 数据会被后台线程随时推进，断言就不再只属于本用例自己的视频。
 * 因此这里改用生产 {@link VideoStatusSchedulingConfig} 注册的三条 {@link ThreadPoolTaskScheduler}
 * Bean，由测试在需要时 {@code scheduleWithFixedDelay} 调用<b>真实 Bean 的真实方法</b>，
 * 并在 {@code finally} 里取消任务、释放门闩。这样既跑的是生产调度器线程（线程名/线程数/隔离属性
 * 全部来自生产配置），又能保证“测试没启动的调度不会在后台偷偷跑”。</p>
 *
 * <p>只碰本用例自己新建的视频：不清理任何历史 dirty/Outbox/消费事件；
 * 其它历史数据可能被同一批真实调度顺手处理，这是允许的，本类不对它们做断言。</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusSchedulingRealFlowTests.SchedulingRealFlowApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
class VideoStatusSchedulingRealFlowTests {

    /** T06 验收口径：publish 必须被真实阻塞 30 秒（不是抛异常、也不是短暂 sleep）。 */
    private static final long BLOCKED_PUBLISH_MILLIS = Duration.ofSeconds(30).toMillis();

    /** T06 验收口径：阻塞期间落库/恢复必须在 10 秒测试预算内继续。 */
    private static final Duration OTHER_ROLE_BUDGET = Duration.ofSeconds(10);

    /** 每个用例自己的事件条数。 */
    private static final int EVENTS = 100;

    /**
     * 复用已有隔离 IT 的真实组件装配（真实 DataSource/Redis/RabbitMQ、真实 Mapper、
     * 真实事务模板、真实 MQ 消费者），只额外补两样东西：
     * 生产 {@link VideoStatusSchedulingConfig} 的三条调度器 Bean，以及测试上下文里
     * 尚未注册的真实 {@link VideoStatusDirtyRecoveryScheduler} Bean。
     */
    @Configuration(proxyBeanMethods = false)
    @Import({VideoStatusIsolatedIntegrationTests.TestApplication.class,
            VideoStatusSchedulingConfig.class})
    static class SchedulingRealFlowApplication {

        @Bean
        VideoStatusDirtyRecoveryScheduler videoStatusDirtyRecoveryScheduler(
                VideoStatusProperties properties,
                VideoStatusConsumedEventMapper consumedEventMapper,
                RedisSetOperations redisSetOperations
        ) {
            return new VideoStatusDirtyRecoveryScheduler(
                    properties,
                    consumedEventMapper,
                    redisSetOperations
            );
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired VideoStatusService stats;
    @Autowired VideoStatusConsumptionService consumption;
    @Autowired VideoStatusEventFingerprintService fingerprint;
    @Autowired VideoStatusOutboxRelay relay;
    @Autowired VideoStatusBatchFlushScheduler flushScheduler;
    @Autowired VideoStatusDirtyRecoveryScheduler recoveryScheduler;
    @Autowired VideoStatusBatchFlushService flushService;
    @Autowired VideoStatusProperties properties;
    @Autowired TransactionTemplate tx;
    @Autowired RabbitTemplate rabbit;
    @Autowired RedisSetOperations redisSetOperations;
    @Autowired VideoStatusConsumedEventMapper consumedMapper;
    @Autowired RedissonClient redissonClient;
    @Autowired org.springframework.context.ApplicationContext context;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier(
            VideoStatusSchedulingConfig.RELAY_SCHEDULER_BEAN)
    ThreadPoolTaskScheduler relayTaskScheduler;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier(
            VideoStatusSchedulingConfig.FLUSH_SCHEDULER_BEAN)
    ThreadPoolTaskScheduler flushTaskScheduler;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier(
            VideoStatusSchedulingConfig.RECOVERY_SCHEDULER_BEAN)
    ThreadPoolTaskScheduler recoveryTaskScheduler;

    /** 真实发布器：spy 只为“把真实 publish 阻塞 30 秒”，阻塞结束后仍调用真实网络发布。 */
    @MockitoSpyBean VideoStatusEventPublisher publisher;

    /** 真实批量落库实现：spy 只为让第一个调度器持锁停留一段时间，便于制造真实锁竞争。 */
    @MockitoSpyBean VideoStatusBatchFlushServiceImpl batchFlushServiceImpl;

    @BeforeEach
    void seed() {
        // 固定隔离端点，且必须真的是 13307 那一套；不读 application.yml。
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                            .startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"),
                    "本类只允许跑在隔离 MySQL 上");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        // 上一次中断运行可能残留消息，先清掉自己的三条队列，避免把历史消息算进本用例。
        RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
        for (String queue : List.of(RabbitConstants.MAIN_QUEUE, RabbitConstants.RETRY_QUEUE,
                RabbitConstants.DEAD_QUEUE)) {
            admin.purgeQueue(queue, false);
        }
    }

    // ------------------------------------------------------------------ 用例 1

    /**
     * 真实链路：Outbox → 真实 relay → 真实 RabbitMQ → 真实消费者（100 条事件 status=1），
     * 然后让真实 {@link VideoStatusEventPublisher#publish} 被门闩阻塞 30 秒。
     *
     * <p>阻塞期间（10 秒预算内）断言：</p>
     * <ol>
     *   <li>真实落库调度在自己的生产线程上把 100 条事件推进到 FLUSHED(2)，DB 基线 = 100；</li>
     *   <li>真实 dirty 恢复调度继续心跳（真实方法在恢复线程上反复执行）；</li>
     *   <li>投递线程仍被阻塞、没有重入，且两条其它线程名与投递线程名互不相同。</li>
     * </ol>
     *
     * <p>阻塞满 30 秒后释放：真实 publish 继续走网络发布并成功，被阻塞的那条 Outbox 最终
     * status=2、事件进入消费表，证明阻塞只是“等待”，没有改变发布语义。</p>
     */
    @Test
    void blockedRealPublishKeepsRealFlushAndRecoveryAlive() throws Exception {
        int vid = freshVideo();
        int triggerVid = freshVideo();

        // ---------- 1. 真实 Outbox → relay → RabbitMQ → 消费者：100 条事件推进到 status=1 ----------
        tx.executeWithoutResult(session -> {
            for (int i = 0; i < EVENTS; i++) {
                stats.createEvent(vid, VideoStatusEventType.PLAY, 1);
            }
        });
        assertEquals(EVENTS, pendingOutbox(vid), "异步入口先写 100 条 Outbox");
        await(() -> {
            relay.relay();
            return pendingOutbox(vid) == 0;
        }, Duration.ofSeconds(60), "真实 relay 未能投递完 100 条 Outbox");
        await(() -> consumedWithStatus(vid, 1) == EVENTS, Duration.ofSeconds(30),
                "真实消费者未把 100 条事件推进到 status=1");
        assertEquals(EVENTS, consumedRows(vid));
        assertEquals(EVENTS, current(vid), "100 次 +1 必须先在 Redis 生效");
        assertEquals(0, baseline(vid), "落库调度还没启动，DB 基线必须仍为 0");
        assertTrue(dirtyContains(vid), "消费成功后本 vid 必须进入真实 dirty Set");

        // 本上下文没有 @EnableScheduling：生产 @Scheduled 方法不会在后台自己跑。
        assertEquals(0, applicationSchedulingProcessors(),
                "本用例不允许全局 @Scheduled 在后台运行，调度必须由测试显式启动");

        // ---------- 2. 把真实 publish 阻塞住（阻塞后仍调用真实实现） ----------
        CountDownLatch publishEntered = new CountDownLatch(1);
        CountDownLatch publishRelease = new CountDownLatch(1);
        AtomicReference<String> publishThread = new AtomicReference<>();
        AtomicBoolean firstPublish = new AtomicBoolean(true);
        doAnswer(call -> {
            if (firstPublish.compareAndSet(true, false)) {
                publishThread.set(Thread.currentThread().getName());
                publishEntered.countDown();
                if (!publishRelease.await(BLOCKED_PUBLISH_MILLIS + 60_000L,
                        TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("发布阻塞窗口没有被释放");
                }
            }
            // 不改变网络：释放后照常调用真实 publish（真实 RabbitMQ + confirm）。
            return call.callRealMethod();
        }).when(publisher).publish(any(VideoStatusChangedEvent.class), anyString());

        // 给真实 relay 一条可领取的事件（独立视频，避免污染被观测 vid 的计数）。
        tx.executeWithoutResult(session ->
                stats.createEvent(triggerVid, VideoStatusEventType.PLAY, 1));

        AtomicInteger flushRuns = new AtomicInteger();
        AtomicInteger recoveryRuns = new AtomicInteger();
        Set<String> flushThreads = ConcurrentHashMap.newKeySet();
        Set<String> recoveryThreads = ConcurrentHashMap.newKeySet();

        ScheduledFuture<?> relayTask = null;
        ScheduledFuture<?> flushTask = null;
        ScheduledFuture<?> recoveryTask = null;
        try {
            relayTask = relayTaskScheduler.scheduleWithFixedDelay(
                    relay::relay, Duration.ofMillis(200));
            assertTrue(publishEntered.await(OTHER_ROLE_BUDGET.toMillis(), TimeUnit.MILLISECONDS),
                    "真实 relay 未在 10 秒内进入 publish 阻塞");
            long blockedAt = System.nanoTime();

            String relayThread = publishThread.get();
            assertTrue(relayThread.startsWith(relayTaskScheduler.getThreadNamePrefix()),
                    "被阻塞的 publish 必须跑在生产 relay 调度线程上：" + relayThread);

            // ---------- 3. 阻塞期间启动真实落库 / 真实恢复调度 ----------
            flushTask = flushTaskScheduler.scheduleWithFixedDelay(() -> {
                flushThreads.add(Thread.currentThread().getName());
                flushRuns.incrementAndGet();
                flushScheduler.flushDirtyVideos();
            }, Duration.ofMillis(100));
            recoveryTask = recoveryTaskScheduler.scheduleWithFixedDelay(() -> {
                recoveryThreads.add(Thread.currentThread().getName());
                recoveryRuns.incrementAndGet();
                recoveryScheduler.recoverDirtyVids();
            }, Duration.ofMillis(200));

            // ---------- 4. 10 秒预算内：真实 DB 落库 + FLUSHED ----------
            await(() -> baseline(vid) == EVENTS && consumedWithStatus(vid, 2) == EVENTS,
                    OTHER_ROLE_BUDGET, "阻塞期间真实落库未在 10 秒预算内完成");
            long flushMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - blockedAt);
            assertTrue(flushMillis <= OTHER_ROLE_BUDGET.toMillis(),
                    "落库必须在 10 秒测试预算内完成，实际 " + flushMillis + " 毫秒");

            // ---------- 5. 恢复心跳继续（真实方法在恢复线程上反复执行） ----------
            await(() -> recoveryRuns.get() >= 2, OTHER_ROLE_BUDGET,
                    "阻塞期间真实 dirty 恢复调度没有继续心跳");
            assertTrue(flushRuns.get() >= 1, "真实落库调度必须至少跑过一轮");

            // ---------- 6. 阻塞仍在持续：投递任务没结束、没有重入 ----------
            assertEquals(1, publishRelease.getCount(), "publish 必须仍然处于阻塞状态");
            assertFalse(relayTask.isDone(), "被阻塞的投递任务不得提前结束");

            // ---------- 7. 阻塞满 30 秒 ----------
            long remaining = BLOCKED_PUBLISH_MILLIS
                    - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - blockedAt);
            if (remaining > 0) {
                Thread.sleep(remaining);
            }
            assertEquals(1, publishRelease.getCount(), "publish 必须持续阻塞至少 30 秒");
            assertFalse(relayTask.isDone(), "30 秒内投递任务必须一直是同一次执行（无重入）");
            assertTrue(flushRuns.get() >= 2,
                    "阻塞期间落库调度必须持续执行，实际 " + flushRuns.get());
            assertTrue(recoveryRuns.get() >= 2,
                    "阻塞期间恢复调度必须持续执行，实际 " + recoveryRuns.get());
            assertEquals(EVENTS, current(vid), "落库不改写 Redis 派生值");
            assertEquals(EVENTS, baseline(vid));

            // 三个角色各用各的生产线程：阻塞的投递线程 != 落库线程 != 恢复线程
            assertTrue(flushThreads.stream().noneMatch(relayThread::equals),
                    "落库不得跑在被阻塞的投递线程上：" + flushThreads);
            assertTrue(recoveryThreads.stream().noneMatch(relayThread::equals),
                    "恢复不得跑在被阻塞的投递线程上：" + recoveryThreads);
            assertTrue(flushThreads.stream().allMatch(name -> name.startsWith(
                            flushTaskScheduler.getThreadNamePrefix())),
                    "落库必须跑在生产落库调度线程上：" + flushThreads);
            assertTrue(recoveryThreads.stream().allMatch(name -> name.startsWith(
                            recoveryTaskScheduler.getThreadNamePrefix())),
                    "恢复必须跑在生产恢复调度线程上：" + recoveryThreads);
            assertNotEquals(flushTaskScheduler.getThreadNamePrefix(),
                    recoveryTaskScheduler.getThreadNamePrefix());
            assertNotEquals(relayTaskScheduler.getThreadNamePrefix(),
                    flushTaskScheduler.getThreadNamePrefix());

            // ---------- 8. 释放后真实 publish 继续走网络并成功 ----------
            publishRelease.countDown();
            await(() -> consumedRows(triggerVid) >= 1, Duration.ofSeconds(30),
                    "释放阻塞后真实 publish/消费链路必须继续推进");
            await(() -> pendingOutbox(triggerVid) == 0, Duration.ofSeconds(30),
                    "释放阻塞后 Outbox 必须回写为已发送");
            System.out.printf(Locale.ROOT,
                    "T06 blocked publish millis=%d flushMillis=%d flushRuns=%d recoveryRuns=%d%n",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - blockedAt),
                    flushMillis, flushRuns.get(), recoveryRuns.get());
        } finally {
            publishRelease.countDown();
            cancel(relayTask);
            cancel(flushTask);
            cancel(recoveryTask);
        }
    }

    // ------------------------------------------------------------------ 用例 2

    /**
     * 两个<b>独立构造</b>的真实 {@link VideoStatusBatchFlushScheduler}（各自持有自己的
     * {@link VideoStatusVidMutex}，也就是两个不同的 {@link RedissonClient}，但落在同一套
     * Redisson 分布式锁协议/同一个锁 Key 上），共享真实 Mapper、真实事务落库实现与真实 Redis，
     * 并发处理同一个 vid 的 100 条 status=1 事件。
     *
     * <p>断言：</p>
     * <ol>
     *   <li>第一个调度器持锁进入真实落库并被测试留住时，第二个调度器按调度轮 100ms 等待后
     *       拿不到同一把分布式锁：不调用落库实现、不改 DB，只把 dirty 放回；</li>
     *   <li>释放后 100 条事件只落库一次：DB 基线 = 100（不是 200），全部 FLUSHED(2)；</li>
     *   <li>第二个调度器补回 dirty 再跑一轮不会二次计数。</li>
     * </ol>
     */
    @Test
    void twoIndependentSchedulersFlushSameVidExactlyOnce() throws Exception {
        int vid = freshVideo();

        // 真实消费路径（register → 真实 Lua apply → markRedisApplied）：100 条事件 status=1。
        for (int i = 0; i < EVENTS; i++) {
            VideoStatusChangedEvent event = event(vid);
            consumption.register(event, fingerprint.hash(event));
            assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
            consumption.markRedisApplied(event.eventId());
        }
        assertEquals(EVENTS, current(vid));
        assertEquals(0, baseline(vid));
        assertEquals(EVENTS, consumedWithStatus(vid, 1));
        assertTrue(dirtyContains(vid));

        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:16379");
        RedissonClient secondClient = Redisson.create(config);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch slowFlushEntered = new CountDownLatch(1);
        CountDownLatch slowFlushRelease = new CountDownLatch(1);
        try {
            // 两个调度器：同一个分布式锁协议，两个独立的 Redisson 客户端。
            VideoStatusVidMutex mutexA = spy(new VideoStatusVidMutex(redissonClient));
            VideoStatusVidMutex mutexB = spy(new VideoStatusVidMutex(secondClient));
            VideoStatusBatchFlushScheduler schedulerA = new VideoStatusBatchFlushScheduler(
                    properties, redisSetOperations, flushService, consumedMapper, mutexA);
            VideoStatusBatchFlushScheduler schedulerB = new VideoStatusBatchFlushScheduler(
                    properties, redisSetOperations, flushService, consumedMapper, mutexB);

            AtomicBoolean slow = new AtomicBoolean(true);
            doAnswer(call -> {
                Integer flushedVid = call.getArgument(0);
                if (flushedVid != null && flushedVid == vid && slow.compareAndSet(true, false)) {
                    slowFlushEntered.countDown();
                    if (!slowFlushRelease.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("慢落库窗口没有被释放");
                    }
                }
                return call.callRealMethod();
            }).when(batchFlushServiceImpl).flushOneVideo(anyInt(), anyInt());

            // 第一个调度器：真实扫描 dirty → 真实持锁 → 真实落库（被测试留住）。
            Future<?> first = pool.submit(() -> {
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (slowFlushEntered.getCount() > 0 && System.nanoTime() < deadline) {
                    schedulerA.flushDirtyVideos();
                    if (slowFlushEntered.getCount() > 0) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            });
            assertTrue(slowFlushEntered.await(20, TimeUnit.SECONDS),
                    "第一个调度器未持锁进入真实落库");

            // 模拟并发消费者/恢复把同一个 vid 再次标记 dirty，让第二个调度器也扫到它。
            redisSetOperations.add(RedisKeyUtils.dirtyVideo(), String.valueOf(vid));
            Future<?> second = pool.submit(() -> {
                // 只跑一小段窗口：证明第二个调度器在这段时间内反复尝试同一把分布式锁都超时即可。
                long deadline = System.nanoTime() + Duration.ofMillis(600).toNanos();
                while (System.nanoTime() < deadline) {
                    schedulerB.flushDirtyVideos();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            second.get(30, TimeUnit.SECONDS);

            assertFalse(first.isDone(), "第一个调度器必须仍停在真实落库里（锁被它持有）");
            // 第二个调度器只等 100ms：拿不到同一把分布式锁，不得进入落库实现。
            verify(batchFlushServiceImpl, times(1)).flushOneVideo(eq(vid), anyInt());
            verify(mutexB, atLeastOnce()).withLock(eq(vid),
                    eq(VideoStatusVidMutex.SCHEDULER_WAIT_TIME_MILLIS), any(Runnable.class));
            assertEquals(0, baseline(vid), "拿不到锁的调度器不得写入 DB");
            assertEquals(EVENTS, consumedWithStatus(vid, 1), "拿不到锁的调度器不得推进事件状态");
            assertTrue(dirtyContains(vid), "拿不到锁必须把 dirty 放回，留给下一轮");

            // 释放第一个调度器：100 条事件只能落库一次。
            slowFlushRelease.countDown();
            first.get(30, TimeUnit.SECONDS);
            assertEquals(EVENTS, baseline(vid),
                    "两个调度器并发处理同一 vid 的 100 条事件只能落库一次");
            assertEquals(EVENTS, current(vid));
            assertEquals(EVENTS, consumedWithStatus(vid, 2));
            assertEquals(0, consumedWithStatus(vid, 1));

            // 第二个调度器补回的 dirty 再跑一轮：没有待刷事件，不得二次计数。
            schedulerB.flushDirtyVideos();
            assertEquals(EVENTS, baseline(vid), "补跑一轮不得二次计数");
            assertEquals(EVENTS, current(vid));
            assertEquals(EVENTS, consumedWithStatus(vid, 2));
            verify(mutexA, atLeastOnce()).withLock(eq(vid),
                    eq(VideoStatusVidMutex.SCHEDULER_WAIT_TIME_MILLIS), any(Runnable.class));
            System.out.printf(Locale.ROOT,
                    "T06 concurrent flush vid=%d baseline=%d flushed=%d%n",
                    vid, baseline(vid), consumedWithStatus(vid, 2));
        } finally {
            slowFlushRelease.countDown();
            pool.shutdownNow();
            secondClient.shutdown();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 本用例自己的新视频：新用户 + 新视频 + 基线 0，不碰任何历史行。 */
    int freshVideo() {
        String username = "it-realflow-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')",
                username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 'isolated-realflow', 'test', 'test', '', '', '', '')", uid);
        int newVid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times) VALUES (?,0)", newVid);
        return newVid;
    }

    VideoStatusChangedEvent event(int vid) {
        return new VideoStatusChangedEvent(UUID.randomUUID().toString(), vid,
                VideoStatusEventType.PLAY, 1L, 1D, LocalDateTime.now(), 2, null);
    }

    long current(int vid) {
        Object value = redis.opsForHash().get(RedisKeyUtils.videoStatus(vid), "playTimes");
        return Long.parseLong((String) Objects.requireNonNull(value));
    }

    long baseline(int vid) {
        return jdbc.queryForObject("SELECT play_times FROM video_status WHERE vid=?", Long.class, vid);
    }

    int consumedRows(int vid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event WHERE vid=?",
                Integer.class, vid);
    }

    int consumedWithStatus(int vid, int status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event "
                + "WHERE vid=? AND process_status=?", Integer.class, vid, status);
    }

    int pendingOutbox(int vid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_outbox "
                + "WHERE aggregate_id=? AND status IN (0,1)", Integer.class, vid);
    }

    boolean dirtyContains(int vid) {
        return redisSetOperations.isMember(RedisKeyUtils.dirtyVideo(), String.valueOf(vid));
    }

    /** 本上下文没有 @EnableScheduling，因此不应存在处理 @Scheduled 的后处理器。 */
    int applicationSchedulingProcessors() {
        return context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class).length;
    }

    void await(BooleanSupplier condition, Duration timeout, String message) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }
}
