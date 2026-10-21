package com.feibijiubi.backend.service.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.config.*;
import com.feibijiubi.backend.event.*;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mq.*;
import com.feibijiubi.backend.service.impl.video.videostatus.*;
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
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
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
            VideoStatusMessageForwarder.class, VideoStatusEventPublisher.class,
            VideoStatusOutboxClaimService.class, VideoStatusOutboxRelay.class,
            VideoStatusBatchFlushScheduler.class, RedisUtils.class, JsonUtils.class,
            RedisHashOperations.class, RedisSetOperations.class})
    static class TestApplication {
        @Bean(destroyMethod = "shutdown")
        RedissonClient redissonClient() {
            Config config = new Config();
            config.useSingleServer().setAddress("redis://127.0.0.1:16379");
            return Redisson.create(config);
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired VideoStatusService stats;
    @Autowired VideoStatusConsumptionService consumption;
    @Autowired VideoStatusEventFingerprintService fingerprint;
    @Autowired VideoStatusBatchFlushScheduler scheduler;
    @Autowired VideoStatusOutboxRelay relay;
    @Autowired TransactionTemplate tx;
    @Autowired RabbitTemplate rabbit;
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
        RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
        for (String queue : List.of(RabbitConstants.MAIN_QUEUE, RabbitConstants.RETRY_QUEUE,
                RabbitConstants.DEAD_QUEUE)) admin.purgeQueue(queue, false);
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
            return row != null && row.getProcessStatus() == 1;
        });
        await(() -> { scheduler.flushDirtyVideos(); return baseline() == 1; });
        assertEquals(1, current());
        await(this::queuesAreIdle);
    }

    /**
     * T01 真实阻塞窗口：不再用“手工 stats.apply 但不 mark”构造中间态，而是让真实 Rabbit 消费者
     * 在 {@code consumption.markRedisApplied} 之前被 latch 挡住（spy 的 doAnswer 阻塞真实方法调用），
     * 主线程等到 latch 确认后才继续跑 -1 与 flush。
     *
     * <p>每轮（共 {@link #WINDOW_ROUNDS} 轮，每轮独立视频 + 独立 DB/Redis Oracle，不靠 sleep）：</p>
     * <ol>
     *   <li>+1 发布到主队列，真实监听线程执行 register → stats.apply（Redis 已 +1）→ 到达
     *       markRedisApplied 阻塞点并 countDown；</li>
     *   <li>窗口内 Oracle：Redis=1，+1 事件仍为 RECEIVED(0)，状态提交确实尚未发生；</li>
     *   <li>主线程走 -1 的完整消费链（Redis=0，-1 事件=1，先于 +1 可见）；</li>
     *   <li>flush 只看到 -1，DB 基线 0；</li>
     *   <li>释放阻塞，+1 状态提交完成；再次 flush 后按实际分支断言。</li>
     * </ol>
     *
     * <p>当前隔离 MySQL 默认（{@code BIGINT UNSIGNED} + 严格模式）下 -1 触发 1690 →
     * {@code DataAccessException}，事件保持 status 1 并重新入 dirty，+1 可见后同批净零收敛。
     * 本用例保留分支 A（status 3 隔离）的精确断言，但不声称该分叉必然发生或永久存在。</p>
     */
    @Test
    void realConsumerBlockedBeforeMarkRedisAppliedKeepsLegalMinusOnePending() throws Exception {
        int pendingBranch = 0;
        int quarantinedBranch = 0;
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
                int firstAttempt = consumedMapper.selectByEventId(minus.eventId()).getProcessStatus();
                assertNotEquals(2, firstAttempt, "合法的 -1 事件不允许在未落库时被标记为 FLUSHED");
                assertTrue(firstAttempt == 1 || firstAttempt == 3,
                        "首轮 flush 后 -1 只能保持待刷(1)或被隔离(3)，实际=" + firstAttempt);

                release.countDown();
                await(() -> consumedMapper.selectByEventId(plus.eventId()).getProcessStatus() == 1);
                assertEquals(0, current(), "释放窗口只提交状态，不再改动 Redis（-1 已应用）");
                // flush 触发不是本用例的被测对象：显式放回 dirty，保证下一次刷库一定执行。
                redis.opsForSet().add(RedisKeyUtils.dirtyVideo(), String.valueOf(vid));
                scheduler.flushDirtyVideos();

                if (firstAttempt == 3) {
                    quarantinedBranch++;
                    assertEquals(1, baseline(), "分支 A：-1 被隔离后 +1 单独落库，DB=1");
                    assertEquals(0, current());
                    assertEquals(3, consumedMapper.selectByEventId(minus.eventId()).getProcessStatus());
                    assertEquals(2, consumedMapper.selectByEventId(plus.eventId()).getProcessStatus());
                } else {
                    pendingBranch++;
                    assertEquals(0, baseline(), "分支 B：同批净零后 DB 保持 0");
                    assertEquals(0, current());
                    assertEquals(2, consumedMapper.selectByEventId(minus.eventId()).getProcessStatus(),
                            "分支 B：-1 必须延后到 +1 可见后完成状态迁移");
                    assertEquals(2, consumedMapper.selectByEventId(plus.eventId()).getProcessStatus());
                }
            } finally {
                release.countDown();
            }
        }
        assertEquals(WINDOW_ROUNDS, pendingBranch + quarantinedBranch);
        System.out.printf(Locale.ROOT,
                "T01 blocked window rounds=%d pendingBranch=%d quarantinedBranch=%d%n",
                WINDOW_ROUNDS, pendingBranch, quarantinedBranch);
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
}
