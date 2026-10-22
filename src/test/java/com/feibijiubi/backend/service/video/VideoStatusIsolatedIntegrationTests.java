package com.feibijiubi.backend.service.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.config.*;
import com.feibijiubi.backend.event.*;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mq.*;
import com.feibijiubi.backend.service.impl.video.UserVideoServiceImpl;
import com.feibijiubi.backend.service.impl.video.videostatus.*;
import com.feibijiubi.backend.service.video.UserVideoService;
import com.feibijiubi.backend.service.video.videostatus.*;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import com.feibijiubi.backend.utils.redis.*;
import com.feibijiubi.backend.utils.redis.operation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in; real Docker services, no application.yml and no production cleanup. */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusIsolatedIntegrationTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
// This class runs a real queue listener; close the context at class end so a
// cached context cannot keep consuming messages during the next IT class.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VideoStatusIsolatedIntegrationTests {
    /** Runs before beans/listeners can connect, including environment-variable overrides. */
    static class IsolationGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var env = context.getEnvironment();
            Map<String, String> required = Map.of(
                    "spring.datasource.url", "jdbc:mysql://127.0.0.1:13307/feibijiubi?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                    "spring.datasource.username", "root",
                    "spring.datasource.password", "isolated-test-only",
                    "spring.data.redis.host", "127.0.0.1",
                    "spring.data.redis.port", "16379",
                    "spring.rabbitmq.host", "127.0.0.1",
                    "spring.rabbitmq.port", "15673",
                    "spring.rabbitmq.username", "isolated-test",
                    "spring.rabbitmq.password", "isolated-test-only");
            required.forEach((key, expected) -> {
                if (!expected.equals(env.getProperty(key))) {
                    throw new IllegalStateException("Refusing non-isolated test configuration: " + key);
                }
            });
            for (String key : List.of("spring.data.redis.url", "spring.data.redis.cluster.nodes",
                    "spring.data.redis.sentinel.master", "spring.rabbitmq.addresses",
                    "spring.datasource.jndi-name", "spring.datasource.hikari.jdbc-url")) {
                if (env.containsProperty(key)) throw new IllegalStateException("Unexpected connection override: " + key);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("video-status-isolated")
    @EnableAutoConfiguration(excludeName = "org.redisson.spring.starter.RedissonAutoConfigurationV2")
    @EnableConfigurationProperties(VideoStatusProperties.class)
    @MapperScan("com.feibijiubi.backend.mapper")
    @EnableRabbit
    @Import({RedisScriptConfig.class, VideoStatusRabbitConfig.class,
            VideoStatusServiceImpl.class, VideoStatusConsumptionServiceImpl.class,
            VideoStatusRebuildServiceImpl.class, VideoStatusRebuildSnapshotServiceImpl.class,
            VideoStatusBatchFlushServiceImpl.class, VideoStatusVidMutex.class,
            VideoStatusEventFingerprintServiceImpl.class, VideoStatusEventConsumer.class,
            VideoStatusConsumerRecoveryController.class,
            VideoStatusMessageForwarder.class, VideoStatusEventPublisher.class,
            VideoStatusOutboxClaimService.class, VideoStatusOutboxRelay.class,
            UserVideoServiceImpl.class,
            RedisUtils.class, JsonUtils.class,
            RedisHashOperations.class, RedisSetOperations.class})
    static class TestApplication {
        @Bean(destroyMethod = "shutdown")
        RedissonClient redissonClient() {
            Config config = new Config();
            config.useSingleServer().setAddress("redis://127.0.0.1:16379");
            return Redisson.create(config);
        }

        /** T03：可推进时钟，用来验证延后退避到期，不靠 sleep。 */
        @Bean
        MutableClock videoStatusFlushClock() {
            return new MutableClock();
        }

        @Bean
        VideoStatusBatchFlushScheduler videoStatusBatchFlushScheduler(
                VideoStatusProperties properties,
                RedisSetOperations redisSetOperations,
                VideoStatusBatchFlushService batchFlushService,
                VideoStatusConsumedEventMapper consumedEventMapper,
                VideoStatusVidMutex vidMutex,
                MutableClock clock
        ) {
            return new VideoStatusBatchFlushScheduler(
                    properties,
                    redisSetOperations,
                    batchFlushService,
                    consumedEventMapper,
                    vidMutex,
                    clock
            );
        }
    }

    /** 可控时钟：每轮窗口推进固定毫秒数，使延后退避确定到期。 */
    static final class MutableClock extends java.time.Clock {
        private final java.util.concurrent.atomic.AtomicLong millis =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(millis.get());
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired VideoStatusService stats;
    @Autowired VideoStatusConsumptionService consumption;
    @Autowired VideoStatusEventFingerprintService fingerprint;
    @Autowired VideoStatusBatchFlushScheduler scheduler;
    @Autowired com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService flushService;
    @Autowired MutableClock flushClock;
    @Autowired VideoStatusOutboxRelay relay;
    @Autowired UserVideoService userVideoService;
    @Autowired VideoStatusProperties properties;
    @Autowired TransactionTemplate tx;
    @Autowired RabbitTemplate rabbit;
    @Autowired org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired ObjectMapper json;
    @Autowired VideoStatusRebuildService rebuild;
    @Autowired VideoStatusConsumedEventMapper consumedMapper;
    @MockitoSpyBean VideoStatusRebuildSnapshotServiceImpl snapshots;
    @MockitoSpyBean VideoStatusVidMutex mutex;
    @MockitoSpyBean VideoStatusConsumptionServiceImpl consumptionSpy;
    /** 真实阻塞窗口重复轮数；每轮使用独立视频，独立 DB 与 Redis 状态。 */
    private static final int WINDOW_ROUNDS = 100;
    int vid;

    @BeforeEach
    void seed() {
        // Fixed isolated endpoints are deliberately not parameterized. Check schema marker too.
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        String username = "it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 'isolated-test', 'test', 'test', '', '', '', '')", uid);
        vid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times) VALUES (?,100)", vid);
        // Avoid stale queue messages from a previous interrupted test run.
        // Stop before purging: purge cannot remove prefetched/unacked messages.
        // Closing this test context's channels returns them to the isolated queues.
        listenerRegistry.stop();
        RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
        // A newer test candidate may have left delayed messages in the isolated broker.
        // Purge only the two known test-project retry tiers, never DB evidence.
        for (String queue : List.of("video.status.retry.queue.30s.v1",
                "video.status.retry.queue.120s.v1")) {
            if (admin.getQueueProperties(queue) != null) admin.purgeQueue(queue, false);
        }
        for (String queue : List.of(RabbitConstants.MAIN_QUEUE, RabbitConstants.RETRY_QUEUE,
                RabbitConstants.RETRY_QUEUE_30S, RabbitConstants.RETRY_QUEUE_120S,
                RabbitConstants.DEAD_QUEUE)) admin.purgeQueue(queue, false);
        listenerRegistry.start();
    }

    @Test
    void upperBoundarySplitBatchMakesProgressUsingRealNegativeFirstQuery() {
        jdbc.update("UPDATE video_status SET play_times=? WHERE vid=?", Long.MAX_VALUE, vid);
        rebuild.ensureInitialized(vid);
        var minus = new VideoStatusChangedEvent(UUID.randomUUID().toString(), vid,
                VideoStatusEventType.PLAY, -1L, -1D, LocalDateTime.now(), 2, null);
        consumption.register(minus, fingerprint.hash(minus));
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(minus));
        var plus = event();
        applyAndMark(plus);
        assertEquals(Long.MAX_VALUE, current());
        assertThrows(com.feibijiubi.backend.common.VideoStatusFlushDeferredException.class,
                () -> mutex.withLock(vid, () -> flushService.flushOneVideo(vid, 1)));
        assertEquals(Long.MAX_VALUE, baseline());
        assertEquals(1, consumedMapper.selectByEventId(plus.eventId()).getProcessStatus());
        consumption.markRedisApplied(minus.eventId());
        mutex.withLock(vid, () -> flushService.flushOneVideo(vid, 1));
        assertEquals(Long.MAX_VALUE - 1, baseline());
        assertEquals(2, consumedMapper.selectByEventId(minus.eventId()).getProcessStatus());
        assertEquals(1, consumedMapper.selectByEventId(plus.eventId()).getProcessStatus());
        mutex.withLock(vid, () -> flushService.flushOneVideo(vid, 1));
        assertEquals(Long.MAX_VALUE, baseline());
        assertEquals(Long.MAX_VALUE, current());
        assertEquals(2, consumedMapper.selectByEventId(plus.eventId()).getProcessStatus());
    }

    /** 每轮窗口的独立 Oracle：新用户/新视频/play_times=0，Redis 键从未被写过。 */
    int freshVideo() {
        String username = "it-window-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 'isolated-test', 'test', 'test', '', '', '', '')", uid);
        int newVid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times) VALUES (?,0)", newVid);
        return newVid;
    }

    /**
     * 维护演练用视频：已发布（status=1）且 duration&gt;0，真实 {@link UserVideoService}
     * 的异步入口与同步基线入口都能通过生产校验。
     */
    int freshPublishedVideo() {
        String username = "it-drill-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key,duration,status) "
                + "VALUES (?, 'isolated-drill', 'test', 'test', '', '', '', '', 12.5, 1)", uid);
        int newVid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times) VALUES (?,0)", newVid);
        return newVid;
    }

    VideoStatusChangedEvent event() {
        return new VideoStatusChangedEvent(UUID.randomUUID().toString(), vid,
                VideoStatusEventType.PLAY, 1L, 1D, LocalDateTime.now(), 2, null);
    }

    void applyAndMark(VideoStatusChangedEvent e) {
        consumption.register(e, fingerprint.hash(e));
        assertTrue(Set.of(VideoStatusService.ApplyResult.APPLIED,
                VideoStatusService.ApplyResult.DUPLICATE).contains(stats.apply(e)));
        consumption.markRedisApplied(e.eventId());
    }

    long current() {
        return Long.parseLong((String) redis.opsForHash().get(RedisKeyUtils.videoStatus(vid), "playTimes"));
    }

    long baseline() {
        return jdbc.queryForObject("SELECT play_times FROM video_status WHERE vid=?", Long.class, vid);
    }

    // ---------------------------------------------------------------- T04 维护演练探针

    int outboxRows(Integer forVid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_outbox WHERE aggregate_id=?",
                Integer.class, forVid);
    }

    int pendingOutbox(Integer forVid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_outbox "
                + "WHERE aggregate_id=? AND status IN (0,1)", Integer.class, forVid);
    }

    int outboxSent(Integer forVid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_outbox "
                + "WHERE aggregate_id=? AND status=2", Integer.class, forVid);
    }

    int consumedRows(Integer forVid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event WHERE vid=?",
                Integer.class, forVid);
    }

    int unfinishedConsumed(Integer forVid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event "
                + "WHERE vid=? AND process_status IN (0,1)", Integer.class, forVid);
    }

    int consumedFlushed(Integer forVid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event "
                + "WHERE vid=? AND process_status=2", Integer.class, forVid);
    }

    /**
     * 维护窗口“排空”：投递角色把本 vid 的 Outbox 0/1 全部发布，消费者把事件推进到 1，
     * 落库调度推进到 2，最后确认主/重试/死信队列 ready 与 unacked 都为 0。
     * 只针对本测试 vid，不清理其他用例或部署的数据。
     */
    void drainOwnPipeline() throws Exception {
        await(() -> {
            relay.relay();
            return pendingOutbox(vid) == 0 && queuesAreIdle();
        });
        await(() -> {
            scheduler.flushDirtyVideos();
            return unfinishedConsumed(vid) == 0;
        });
        await(this::queuesAreIdle);
        assertEquals(0, pendingOutbox(vid), "排空后本 vid 不得残留 Outbox status=0/1");
        assertEquals(0, unfinishedConsumed(vid), "排空后本 vid 不得残留 process_status=0/1");
    }

    /**
     * 维护演练的重建步骤：持本 vid 锁 → 确认没有未完成事件 → 删除本测试 vid 的 current 派生缓存 →
     * 调用真实 {@code VideoStatusRebuildService#ensureInitialized} 从 DB 基线 + 已确认事件重建。
     *
     * <p>这是<b>测试内</b>的维护动作，不是生产能力：生产没有受控强制重建入口（见运行角色文档 §7）。</p>
     */
    void rebuildCurrentCacheUnderLock() {
        mutex.withLock(vid, () -> {
            assertEquals(0, pendingOutbox(vid), "持锁时必须确认没有未投递 Outbox");
            assertEquals(0, unfinishedConsumed(vid), "持锁时必须确认没有未落库消费事件");
            String currentKey = RedisKeyUtils.videoStatus(vid);
            assertTrue(Boolean.TRUE.equals(redis.delete(currentKey)),
                    "删除前 current 必须存在；且只删除本测试 vid 的派生缓存");
            assertFalse(Boolean.TRUE.equals(redis.hasKey(currentKey)));
            // Redisson 可重入锁：ensureInitialized 在同一线程再次获取同一把 vid 锁。
            rebuild.ensureInitialized(vid);
            assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyUtils.videoStatusRebuildGate(vid))),
                    "重建必须释放门闩");
        });
    }

    void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "condition did not converge in 30 seconds");
    }

    @Test
    void hotPathDoesNotAcquireVidLockEvenWhileFlushOwnsIt() throws Exception {
        rebuild.ensureInitialized(vid);
        clearInvocations(mutex);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> mutex.withLock(vid, () -> {
            held.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
        }));
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS));
            clearInvocations(mutex);
            applyAndMark(event());
            assertEquals(101, current());
            verifyNoInteractions(mutex);
        } finally {
            release.countDown(); holder.get(10, TimeUnit.SECONDS); pool.shutdownNow();
        }
    }

    @Test
    void concurrentColdStartAndDuplicateDeliveryConverge() throws Exception {
        List<VideoStatusChangedEvent> events = new ArrayList<>();
        for (int i = 0; i < 100; i++) events.add(event());
        List<VideoStatusChangedEvent> deliveries = new ArrayList<>(events);
        deliveries.addAll(events); // every event is delivered twice
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (var e : deliveries) futures.add(pool.submit(() -> applyAndMark(e)));
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(200, current());
        assertEquals(200D, redis.opsForZSet().score(RedisKeyUtils.feedHotVideos(), "" + vid));
        scheduler.flushDirtyVideos();
        assertEquals(200, baseline());
        assertEquals(100, jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event "
                + "WHERE vid=? AND process_status=2", Integer.class, vid));
    }

    @Test
    void redisSuccessBeforeStatusMarkIsRecoveredWithoutDoubleCounting() {
        var e = event();
        consumption.register(e, fingerprint.hash(e));
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(e));
        // Simulate a crash before markRedisApplied, then lose only current.
        redis.delete(RedisKeyUtils.videoStatus(vid));
        applyAndMark(e);
        assertEquals(101, current());
        scheduler.flushDirtyVideos();
        assertEquals(101, baseline());
    }

    @Test
    void pendingAndFlushedEventsRebuildFromConsistentBaseline() {
        applyAndMark(event());
        scheduler.flushDirtyVideos();
        applyAndMark(event());
        redis.delete(RedisKeyUtils.videoStatus(vid));
        rebuild.ensureInitialized(vid);
        assertEquals(102, current());
        scheduler.flushDirtyVideos();
        assertEquals(102, baseline());
    }

    @Test
    void abandonedGateIsTakenOverWithoutExpiry() {
        rebuild.ensureInitialized(vid);
        redis.opsForValue().set(RedisKeyUtils.videoStatusRebuildGate(vid), "crashed-owner");
        applyAndMark(event());
        assertEquals(101, current());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyUtils.videoStatusRebuildGate(vid))));
    }

    @Test
    void gateBlocksConsumptionDuringSnapshotAndNewRegistrationIsNotLost() throws Exception {
        applyAndMark(event());
        redis.delete(RedisKeyUtils.videoStatus(vid));
        var e = event();
        CountDownLatch loaded = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> {
            Object snapshot = call.callRealMethod();
            loaded.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return snapshot;
        }).when(snapshots).load(vid);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> rebuilding = pool.submit(() -> rebuild.ensureInitialized(vid));
            assertTrue(loaded.await(5, TimeUnit.SECONDS));
            Future<?> consuming = pool.submit(() -> applyAndMark(e));
            CountDownLatch flushing = new CountDownLatch(1);
            Future<?> flush = pool.submit(() -> { flushing.countDown(); scheduler.flushDirtyVideos(); });
            assertTrue(flushing.await(5, TimeUnit.SECONDS));
            await(() -> consumedMapper.selectByEventId(e.eventId()) != null);
            assertFalse(consuming.isDone());
            assertFalse(flush.isDone());
            assertEquals(100, baseline());
            release.countDown();
            rebuilding.get(10, TimeUnit.SECONDS);
            consuming.get(10, TimeUnit.SECONDS);
            flush.get(10, TimeUnit.SECONDS);
            assertEquals(102, current());
            await(() -> { scheduler.flushDirtyVideos(); return baseline() == 102; });
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void staleRebuildCannotPublishOrReleaseAnotherOwnersGate() {
        String gate = RedisKeyUtils.videoStatusRebuildGate(vid);
        redis.opsForValue().set(gate, "new-owner");
        DefaultRedisScript<String> init = new DefaultRedisScript<>();
        init.setLocation(new ClassPathResource("lua/video-status-init.lua"));
        init.setResultType(String.class);
        assertEquals("STALE_REBUILD", redis.execute(init, List.of(RedisKeyUtils.videoStatus(vid),
                RedisKeyUtils.dirtyVideo(), RedisKeyUtils.feedHotVideos(), gate),
                "" + vid, "100", "100", "0", "0", "0", "0", "0", "0", "0", "false", "old-owner"));
        DefaultRedisScript<Long> release = new DefaultRedisScript<>();
        release.setLocation(new ClassPathResource("lua/video-status-release-gate.lua"));
        release.setResultType(Long.class);
        assertEquals(0L, redis.execute(release, List.of(gate), "old-owner"));
        assertEquals("new-owner", redis.opsForValue().get(gate));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyUtils.videoStatus(vid))));
        redis.delete(gate);
    }

    @Test
    void failedSnapshotReleasesGateAndNextAttemptRecovers() {
        doThrow(new IllegalStateException("injected snapshot failure")).when(snapshots).load(vid);
        assertThrows(IllegalStateException.class, () -> rebuild.ensureInitialized(vid));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyUtils.videoStatusRebuildGate(vid))));
        doCallRealMethod().when(snapshots).load(vid);
        applyAndMark(event());
        assertEquals(101, current());
    }

    @Test
    void concurrentFlushAndConsumptionPreserveAllEvents() throws Exception {
        rebuild.ensureInitialized(vid);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) futures.add(pool.submit(() -> {
                for (int i = 0; i < 50; i++) applyAndMark(event());
            }));
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 100; i++) scheduler.flushDirtyVideos();
            }));
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
            await(() -> { scheduler.flushDirtyVideos(); return baseline() == 300; });
            assertEquals(300, current());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void negativeResultDoesNotSetProcessedOrMutateCurrent() {
        rebuild.ensureInitialized(vid);
        var e = new VideoStatusChangedEvent(UUID.randomUUID().toString(), vid,
                VideoStatusEventType.PLAY, -101L, -101D, LocalDateTime.now(), 2, null);
        assertEquals(VideoStatusService.ApplyResult.NEGATIVE_RESULT, stats.apply(e));
        assertEquals(100, current());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyUtils.processedKey(e.eventId()))));
    }

    @Test
    void retryQueueRedeliversAfterNegativeDependencyArrives() throws Exception {
        var negative = new VideoStatusChangedEvent(UUID.randomUUID().toString(), vid,
                VideoStatusEventType.PLAY, -101L, -101D, LocalDateTime.now(), 2, null);
        rabbit.convertAndSend(RabbitConstants.MAIN_EXCHANGE, RabbitConstants.MAIN_ROUTING_KEY, negative);
        await(() -> Objects.requireNonNull(new RabbitAdmin(rabbit.getConnectionFactory())
                .getQueueInfo(RabbitConstants.RETRY_QUEUE)).getMessageCount() > 0);
        var positive = new VideoStatusChangedEvent(UUID.randomUUID().toString(), vid,
                VideoStatusEventType.PLAY, 2L, 2D, LocalDateTime.now(), 2, null);
        applyAndMark(positive);
        await(() -> {
            var row = consumedMapper.selectByEventId(negative.eventId());
            // 重投成功后既可能停在 PENDING_FLUSH(1)，也可能已被后台 flush
            // 推进到 FLUSHED(2)；两者都证明负依赖到达后消息已重新消费。
            return row != null && (row.getProcessStatus() == 1
                    || row.getProcessStatus() == 2);
        });
        await(() -> { scheduler.flushDirtyVideos(); return baseline() == 1; });
        assertEquals(1, current());
        await(this::queuesAreIdle);
    }

    /**
     * T03 真实阻塞窗口：不再用“手工 stats.apply 但不 mark”构造中间态，而是让真实 Rabbit 消费者
     * 在 {@code consumption.markRedisApplied} 之前被 latch 挡住（spy 的 doAnswer 阻塞真实方法调用），
     * 主线程等到 latch 确认后才继续跑 -1 与 flush。
     *
     * <p>每轮（共 {@link #WINDOW_ROUNDS} 轮，每轮独立视频 + 独立 DB/Redis Oracle，不靠 sleep）：</p>
     * <ol>
     *   <li>+1 发布到主队列，真实监听线程执行 register → stats.apply（Redis 已 +1）→ 到达
     *       markRedisApplied 阻塞点并 countDown；</li>
     *   <li>窗口内 Oracle：Redis=1，+1 事件仍为 RECEIVED(0)，状态提交确实尚未发生；</li>
     *   <li>主线程走 -1 的完整消费链（Redis=0，-1 事件=1，先于 +1 可见）；</li>
     *   <li>flush 只看到 -1，DB 基线 0；T03 要求此时<b>延后</b>（status 1 + dirty 保留），
     *       不允许隔离为 status 3，也不允许把统计改成 0；</li>
     *   <li>释放阻塞使 +1 状态提交完成，推进退避时钟后再次 flush：
     *       两个合法事件均 FLUSHED(2)，DB=Redis=0，0 次误隔离。</li>
     * </ol>
     *
     * <p>退避使用 {@link MutableClock} 推进（31 秒 &gt; 规范允许的最大常量退避），
     * 不依赖真实等待；延后时已把 dirty 放回，所以本轮不需要手工补 SADD。</p>
     */
    @Test
    void realConsumerBlockedBeforeMarkRedisAppliedDefersLegalMinusOneThenConverges() throws Exception {
        int deferredRounds = 0;
        int quarantined = 0;
        for (int round = 0; round < WINDOW_ROUNDS; round++) {
            vid = freshVideo();
            var plus = event();
            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(call -> {
                if (plus.eventId().equals(call.getArgument(0))) {
                    blocked.countDown();
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("markRedisApplied 阻塞窗口未被释放");
                    }
                }
                return call.callRealMethod();
            }).when(consumptionSpy).markRedisApplied(anyString());

            try {
                rabbit.convertAndSend(RabbitConstants.MAIN_EXCHANGE, RabbitConstants.MAIN_ROUTING_KEY, plus);
                assertTrue(blocked.await(30, TimeUnit.SECONDS),
                        "真实消费者未到达 markRedisApplied 阻塞点，round=" + round);
                assertEquals(1, current(), "窗口内 Redis 必须已应用 +1");
                assertEquals(0, consumedMapper.selectByEventId(plus.eventId()).getProcessStatus(),
                        "窗口内 +1 事件必须仍为 RECEIVED，状态提交尚未发生");

                var minus = new VideoStatusChangedEvent(UUID.randomUUID().toString(), vid,
                        VideoStatusEventType.PLAY, -1L, -1D, LocalDateTime.now(), 2, null);
                applyAndMark(minus);
                assertEquals(0, current());
                assertEquals(1, consumedMapper.selectByEventId(minus.eventId()).getProcessStatus());

                scheduler.flushDirtyVideos();
                assertEquals(0, baseline(), "非负守卫不允许把 DB 写成负数或部分落库");
                assertEquals(1, consumedMapper.selectByEventId(minus.eventId()).getProcessStatus(),
                        "T03：合法的 -1 只能延后，不允许隔离为 status 3，round=" + round);
                assertEquals(0, consumedMapper.selectByEventId(plus.eventId()).getProcessStatus(),
                        "暂停中的 +1 不能被本批选中，round=" + round);
                assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(
                                RedisKeyUtils.dirtyVideo(), String.valueOf(vid))),
                        "T03：延后必须保留 dirty 标记，round=" + round);
                deferredRounds++;

                release.countDown();
                await(() -> consumedMapper.selectByEventId(plus.eventId()).getProcessStatus() == 1);
                assertEquals(0, current(), "释放窗口只提交状态，不再改动 Redis（-1 已应用）");

                // 退避到期后自动收敛：不手工补 SADD，验证 defer 时保留了 dirty
                flushClock.advance(Duration.ofSeconds(31));
                scheduler.flushDirtyVideos();
                assertEquals(0, baseline(), "同批净零后 DB 保持 0");
                assertEquals(0, current());
                assertEquals(2, consumedMapper.selectByEventId(minus.eventId()).getProcessStatus(),
                        "延后的 -1 必须在 +1 可见后自动收敛，round=" + round);
                assertEquals(2, consumedMapper.selectByEventId(plus.eventId()).getProcessStatus(),
                        "+1 必须 FLUSHED，round=" + round);
                quarantined += jdbc.queryForObject(
                        "SELECT COUNT(*) FROM video_status_consumed_event "
                                + "WHERE vid=? AND process_status=3",
                        Integer.class, vid);
            } finally {
                release.countDown();
            }
        }
        assertEquals(WINDOW_ROUNDS, deferredRounds);
        assertEquals(0, quarantined, "合法事件不允许被误隔离");
        System.out.printf(Locale.ROOT,
                "T03 blocked window rounds=%d deferred=%d quarantined=%d%n",
                WINDOW_ROUNDS, deferredRounds, quarantined);
    }

    @Test
    void malformedMessageGoesToDeadQueueRatherThanBeingSilentlyLost() throws Exception {
        rabbit.send(RabbitConstants.MAIN_EXCHANGE, RabbitConstants.MAIN_ROUTING_KEY,
                new org.springframework.amqp.core.Message("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        await(() -> Objects.requireNonNull(new RabbitAdmin(rabbit.getConnectionFactory())
                .getQueueInfo(RabbitConstants.DEAD_QUEUE)).getMessageCount() == 1);
        assertEquals(100, baseline());
        new RabbitAdmin(rabbit.getConnectionFactory()).purgeQueue(RabbitConstants.DEAD_QUEUE, false);
    }

    @Test
    @EnabledIfSystemProperty(named = "videoStatusBenchmark", matches = "true")
    void compareHotPathWithEquivalentExternalLock() throws Exception {
        rebuild.ensureInitialized(vid);
        // Diagnostic comparison, not a historical-version or production capacity benchmark.
        for (boolean locked : List.of(true, false)) {
            List<Long> latency = Collections.synchronizedList(new ArrayList<>());
            List<VideoStatusChangedEvent> events = new ArrayList<>();
            for (int i = 0; i < 500; i++) events.add(event());
            ExecutorService pool = Executors.newFixedThreadPool(5);
            long start = System.nanoTime();
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (var e : events) futures.add(pool.submit(() -> {
                    long begin = System.nanoTime();
                    consumption.register(e, fingerprint.hash(e));
                    var result = locked ? mutex.withLock(vid, () -> stats.apply(e)) : stats.apply(e);
                    assertEquals(VideoStatusService.ApplyResult.APPLIED, result);
                    consumption.markRedisApplied(e.eventId());
                    latency.add(System.nanoTime() - begin);
                }));
                for (var future : futures) future.get(60, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            double seconds = (System.nanoTime() - start) / 1_000_000_000D;
            latency.sort(Long::compareTo);
            System.out.printf(Locale.ROOT,
                    "HOT_PATH locked=%s events=500 throughput=%.1f/s p95=%.2fms p99=%.2fms%n",
                    locked, 500 / seconds, latency.get(474) / 1_000_000D, latency.get(494) / 1_000_000D);
        }
        assertEquals(1100, current());
        await(() -> { scheduler.flushDirtyVideos(); return baseline() == 1100; });
    }

    boolean queuesAreIdle() {
        try {
            String auth = Base64.getEncoder().encodeToString(
                    "isolated-test:isolated-test-only".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:15674/api/queues/%2F"))
                    .timeout(Duration.ofSeconds(3)).header("Authorization", "Basic " + auth).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            for (var queue : json.readTree(response.body())) {
                if (List.of(RabbitConstants.MAIN_QUEUE, RabbitConstants.RETRY_QUEUE,
                        RabbitConstants.RETRY_QUEUE_30S, RabbitConstants.RETRY_QUEUE_120S,
                        RabbitConstants.DEAD_QUEUE).contains(queue.path("name").asText())) {
                    if (!queue.has("messages") || queue.path("messages").asInt() != 0
                            || queue.path("messages_unacknowledged").asInt() != 0) return false;
                }
            }
            return true;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test
    void realOutboxRabbitConsumerAndFlushConvergeWithDuplicates() throws Exception {
        List<VideoStatusChangedEvent> events = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 100; i++) events.add(stats.createEvent(vid, VideoStatusEventType.PLAY, 1));
        });
        relay.relay();
        for (int i = 0; i < 20; i++) rabbit.convertAndSend(RabbitConstants.MAIN_EXCHANGE,
                RabbitConstants.MAIN_ROUTING_KEY, events.get(i));
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event "
                + "WHERE vid=? AND process_status=1", Integer.class, vid) == 100);
        // SKIP LOCKED may leave a row briefly locked by a duplicate registration.
        await(() -> {
            scheduler.flushDirtyVideos();
            return baseline() == 200;
        });
        assertEquals(200, current());
        assertEquals(200D, redis.opsForZSet().score(RedisKeyUtils.feedHotVideos(), "" + vid));
        assertEquals(100, jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event "
                + "WHERE vid=? AND process_status=2", Integer.class, vid));
        assertEquals(100, jdbc.queryForObject("SELECT COUNT(*) FROM video_status_outbox "
                + "WHERE aggregate_id=? AND status=2", Integer.class, vid));
        await(this::queuesAreIdle);
        scheduler.flushDirtyVideos();
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(RedisKeyUtils.dirtyVideo(), "" + vid)));
    }

    /**
     * T04 维护演练（受控 sync → async → sync），全部走真实开关与真实入口：
     * {@code properties} 是应用实际注入的 {@link VideoStatusProperties} 实例，
     * 业务写入走真实 {@link UserVideoService#increasePlayCount(Integer)}（async=true 时写 Outbox，
     * async=false 时同步落 DB），不是测试自己拼 SQL 模拟。
     *
     * <p>演练顺序（每步都有断言，不靠 sleep）：</p>
     * <ol>
     *   <li>异步入口写 Outbox，不碰 DB/Redis；relay + 真实消费者 + flush 收敛；</li>
     *   <li>停输入，并用真实 {@code outbox-relay-enabled=false} 证明停投：事件留在 Outbox、队列为空；
     *       恢复开关后排空；</li>
     *   <li>切 sync 前显式排空（Outbox 0/1=0、消费 0/1=0、队列 0、本 vid 不在 dirty）；</li>
     *   <li>{@code async-enabled=false} 后用真实同步入口写 DB 基线，验证 Redis 派生值确实落后；</li>
     *   <li>持 vid 锁 + 确认无未完成事件，删除本测试自己的 current，再 ensureInitialized 重建；</li>
     *   <li>恢复 async，下一条事件继续累加：无遗漏、无重复、不被旧缓存覆盖；</li>
     *   <li>再走一轮 sync → 重建 → async，证明流程可重复；</li>
     *   <li>最终校验事件数 / 落库数 / 唯一 eventId / dirty / 热点分。</li>
     * </ol>
     *
     * <p>本用例模拟“维护窗口内的操作顺序”，不证明运行期热切换安全：生产修改配置仍需重启，
     * 且没有受控强制重建入口（见 {@code docs/tutorial/video-status-runtime-roles.md} §4/§7）。</p>
     */
    @Test
    void controlledSyncAsyncSyncMaintenanceDrillConvergesWithoutLossOrStaleCache() throws Exception {
        vid = freshPublishedVideo();
        boolean asyncBefore = properties.isAsyncEnabled();
        boolean relayBefore = properties.isOutboxRelayEnabled();
        try {
            assertTrue(asyncBefore, "隔离集成环境必须以 async-enabled=true 启动");
            assertTrue(relayBefore, "隔离集成环境必须以 outbox-relay-enabled=true 启动");

            // ---------- 1. 异步入口（真实 UserVideoService）只写 Outbox ----------
            userVideoService.increasePlayCount(vid);
            assertEquals(1, outboxRows(vid), "异步入口只写一条 Outbox 事件");
            assertEquals(1, pendingOutbox(vid), "新事件必须先停留在 status=0");
            assertEquals(0, baseline(), "异步入口不得直接改 DB 基线");
            assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyUtils.videoStatus(vid))),
                    "异步入口不得直接改 Redis");
            drainOwnPipeline();
            await(() -> {
                scheduler.flushDirtyVideos();
                return baseline() == 1;
            });
            assertEquals(1, current());
            assertEquals(1, outboxSent(vid));
            assertEquals(1, consumedFlushed(vid));

            // ---------- 2. 停输入 + 真实停投开关：relay 不领取不发布 ----------
            userVideoService.increasePlayCount(vid);
            properties.setOutboxRelayEnabled(false);
            relay.relay();
            assertEquals(1, pendingOutbox(vid), "停投时 Outbox 必须保持 status=0，等待恢复");
            assertEquals(1, consumedRows(vid), "停投时事件不得进入消费表");
            assertTrue(queuesAreIdle(), "停投时不得发布任何消息");
            properties.setOutboxRelayEnabled(true);
            drainOwnPipeline();
            await(() -> {
                scheduler.flushDirtyVideos();
                return baseline() == 2;
            });
            assertEquals(2, current());
            assertEquals(2, outboxSent(vid));
            assertEquals(2, consumedFlushed(vid));

            // ---------- 3. 切 sync 前排空（本 vid 自己的管道） ----------
            drainOwnPipeline();
            assertEquals(0, pendingOutbox(vid));
            assertEquals(0, unfinishedConsumed(vid));
            assertTrue(queuesAreIdle());
            assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(
                    RedisKeyUtils.dirtyVideo(), String.valueOf(vid))), "排空后本 vid 不应是 dirty");

            // ---------- 4. 真实同步入口写 DB 基线；Redis 派生值此时落后 ----------
            properties.setAsyncEnabled(false);
            long baselineBeforeSync = baseline();
            long currentBeforeSync = current();
            int outboxBeforeSync = outboxRows(vid);
            int consumedBeforeSync = consumedRows(vid);
            userVideoService.increasePlayCount(vid);
            assertEquals(baselineBeforeSync + 1, baseline(), "同步路径直接提交 DB 基线");
            assertEquals(currentBeforeSync, current(), "同步路径不写 Redis，派生缓存必须显式重建");
            assertEquals(outboxBeforeSync, outboxRows(vid), "同步路径不得新增 Outbox");
            assertEquals(consumedBeforeSync, consumedRows(vid), "同步路径不得新增消费事件");

            // ---------- 5. 持锁确认无未完成事件，删除自己的 current，ensureInitialized 重建 ----------
            rebuildCurrentCacheUnderLock();
            assertEquals(baseline(), current(), "重建后 Redis current 必须等于 DB 基线");
            assertEquals((double) baseline(), redis.opsForZSet().score(
                    RedisKeyUtils.feedHotVideos(), String.valueOf(vid)), "热点分数同样以基线重建");
            assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(
                    RedisKeyUtils.dirtyVideo(), String.valueOf(vid))));

            // ---------- 6. 恢复 async：下一条事件无遗漏/无重复/不被旧缓存覆盖 ----------
            properties.setAsyncEnabled(true);
            long baselineBeforeAsync = baseline();
            userVideoService.increasePlayCount(vid);
            assertEquals(1, pendingOutbox(vid), "恢复异步后事件先停留在 Outbox");
            drainOwnPipeline();
            await(() -> {
                scheduler.flushDirtyVideos();
                return baseline() == baselineBeforeAsync + 1;
            });
            assertEquals(baselineBeforeAsync + 1, current(),
                    "重建后的缓存必须继续累加，不得回退到旧值");
            assertEquals((double) baseline(), redis.opsForZSet().score(
                    RedisKeyUtils.feedHotVideos(), String.valueOf(vid)));

            // ---------- 7. 第二轮 sync -> 重建，验证流程可重复 ----------
            drainOwnPipeline();
            properties.setAsyncEnabled(false);
            long baselineBeforeSecondSync = baseline();
            userVideoService.increasePlayCount(vid);
            assertEquals(baselineBeforeSecondSync + 1, baseline());
            assertEquals(baselineBeforeSecondSync, current(), "同步写入后缓存仍落后 1");
            rebuildCurrentCacheUnderLock();
            assertEquals(baseline(), current());

            // ---------- 8. 回归 async，最终一次事件验证收敛 ----------
            properties.setAsyncEnabled(true);
            long baselineBeforeFinal = baseline();
            userVideoService.increasePlayCount(vid);
            drainOwnPipeline();
            await(() -> {
                scheduler.flushDirtyVideos();
                return baseline() == baselineBeforeFinal + 1;
            });

            int asyncEvents = 4;
            int syncWrites = 2;
            assertEquals(asyncEvents + syncWrites, baseline(),
                    "总增量 = 4 次异步事件 + 2 次同步基线写入");
            assertEquals(baseline(), current(), "最终 DB 与 Redis 必须一致");
            assertEquals(asyncEvents, outboxRows(vid), "每个异步事件恰好一行 Outbox");
            assertEquals(asyncEvents, outboxSent(vid), "所有 Outbox 必须已发送");
            assertEquals(asyncEvents, consumedRows(vid), "每个事件恰好一行消费记录");
            assertEquals(asyncEvents, consumedFlushed(vid), "所有消费记录必须已落库");
            assertEquals(asyncEvents, jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT event_id) FROM video_status_consumed_event WHERE vid=?",
                    Integer.class, vid), "同一 eventId 不得产生重复消费行");
            assertEquals((double) baseline(), redis.opsForZSet().score(
                    RedisKeyUtils.feedHotVideos(), String.valueOf(vid)), "热点分数与基线一致");
            assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(
                    RedisKeyUtils.dirtyVideo(), String.valueOf(vid))), "收敛后不得残留 dirty");
            System.out.printf(Locale.ROOT,
                    "T04 maintenance drill vid=%d asyncEvents=%d syncWrites=%d baseline=%d current=%d%n",
                    vid, asyncEvents, syncWrites, baseline(), current());
        } finally {
            properties.setAsyncEnabled(asyncBefore);
            properties.setOutboxRelayEnabled(relayBefore);
        }
    }
}
