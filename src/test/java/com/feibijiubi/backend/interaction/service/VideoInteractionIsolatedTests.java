package com.feibijiubi.backend.interaction.service;

import com.feibijiubi.backend.config.RedisScriptConfig;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionServiceImpl;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusRebuildServiceImpl;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusRebuildSnapshotServiceImpl;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusServiceImpl;
import com.feibijiubi.backend.videostats.service.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisHashOperations;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import com.feibijiubi.backend.videostats.service.VideoStatusIsolatedIntegrationTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T15 第一阶段（异步计数路径）的真实隔离 IT：MySQL 13307 + Redis 16379。
 *
 * <p>只在显式 JVM 参数 {@code -DvideoStatusIT=true} 时运行，复用仓库已有的
 * {@link VideoStatusIsolatedIntegrationTests.IsolationGuard} 与 {@code video-status-isolated.properties}，
 * 不加载 application.yml，不连其它环境。每个用例只新建自己的 user/video/video_status 行与
 * 自己随机的 actor 哈希，不删除历史数据、不清库。</p>
 *
 * <h2>覆盖的不变量</h2>
 * <ol>
 *   <li>首次请求必须计数：窗口行 {@code last_counted_at} 初始为 NULL，第一条请求计一次并
 *       产生<b>恰好一条</b> Outbox 事件；{@code video_status} 不被同步更新（异步路径）。</li>
 *   <li>30 分钟滚动窗口边界：{@code -1799s} 抑制且窗口不推进，{@code -1801s} 再计一次并推进。</li>
 *   <li>重复旧 requestKey 永远重放原结果：即使窗口早已过期，也不会重新判定为计数。</li>
 *   <li>并发同一 requestKey 只计一次；并发不同 requestKey 命中同一窗口也只计一次。</li>
 *   <li>Outbox 事件插入失败时，请求行、窗口推进与事件<b>整事务回滚</b>（真实 MySQL，非 Mockito），
 *       故障移除后同一 requestKey 仍能首次计数。</li>
 * </ol>
 *
 * <p>运行：{@code ./mvnw -DvideoStatusIT=true -Dtest=VideoInteractionIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoInteractionIsolatedTests.TestApplication.class,
        properties = {
                "spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none",
                // 本类只按显式调用推进，避免后台调度/relay 与断言竞争。
                "app.video-status.scheduling-enabled=false",
                "app.video-status.outbox-relay-enabled=false"
        })
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VideoInteractionIsolatedTests {

    private static final String FAULT_TRIGGER = "t15_fail_outbox";

    @Configuration(proxyBeanMethods = false)
    @Profile("video-status-isolated")
    @EnableAutoConfiguration(excludeName =
            "org.redisson.spring.starter.RedissonAutoConfigurationV2")
    @EnableConfigurationProperties(VideoStatusProperties.class)
    // R3：交互 mapper 在 com.feibijiubi.backend.interaction.mapper。
    // R4：统计 mapper 迁到 videostats.mapper，旧 com.feibijiubi.backend.mapper 已无接口，
    // 该扫描项按等价集合改为 videostats.mapper（不是缩小扫描范围）。
    @MapperScan({"com.feibijiubi.backend.videostats.mapper", "com.feibijiubi.backend.interaction.mapper"})
    @Import({RedisScriptConfig.class,
            VideoStatusServiceImpl.class, VideoStatusRebuildServiceImpl.class,
            VideoStatusRebuildSnapshotServiceImpl.class, VideoStatusVidMutex.class,
            VideoInteractionServiceImpl.class,
            RedisUtils.class, JsonUtils.class,
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
    @Autowired VideoInteractionService interactionService;
    @Autowired VideoStatusProperties properties;

    int vid;

    @BeforeEach
    void seedOwnVideo() {
        // 上一轮异常退出可能留下故障触发器：先清掉，避免污染后续用例。
        dropFaultTrigger();
        assertTrue(properties.isAsyncEnabled(),
                "本类验证异步计数路径，隔离配置必须是 async-enabled=true");
        String username = "t15-it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) "
                + "VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject(
                "SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 't15-isolated', 'test', 'test', '', '', '', '')", uid);
        vid = jdbc.queryForObject(
                "SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times) VALUES (?,0)", vid);
    }

    @AfterEach
    void dropFaultTriggerAfterTest() {
        dropFaultTrigger();
    }

    // -------------------------------------------------------------- first request

    @Test
    void firstRequestCountsAndCreatesExactlyOneOutboxEvent() {
        String actorHash = randomActorHash();
        String requestKey = randomRequestKey();

        VideoInteractionResult result = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, requestKey));

        assertTrue(result.counted());
        assertEquals(VideoInteractionService.STATE_COUNTED, result.state());
        assertFalse(result.duplicate());
        assertNotNull(result.eventId(), "异步路径必须返回 Outbox 事件的 eventId");

        Map<String, Object> request = requestRow(requestKey);
        assertEquals(1, intOf(request.get("state")));
        assertEquals(result.eventId(), request.get("event_id"));
        assertNotNull(request.get("completed_at"));
        assertEquals("USER", request.get("actor_type"));
        assertEquals("PLAY", request.get("action"));

        Map<String, Object> window = windowRow(actorHash, "PLAY");
        assertNotNull(window.get("last_counted_at"),
                "第一条请求必须计数：NULL 的 last_counted_at 只能代表“从未计数”");

        assertEquals(1, outboxCountForEvent(result.eventId()));
        assertEquals(1, outboxCountForVid(), "一次请求只能产生一条统计事件");
        assertEquals(0L, playTimes(), "异步路径不得同步更新 video_status");
    }

    // ----------------------------------------------------------- 30 minute window

    @Test
    void requestJustInsideWindowIsSuppressedAndDoesNotAdvanceWindow() {
        String actorHash = randomActorHash();
        seedWindow(actorHash, "PLAY", -1799);
        Object before = windowRow(actorHash, "PLAY").get("last_counted_at");

        VideoInteractionResult result = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, randomRequestKey()));

        assertFalse(result.counted());
        assertEquals(VideoInteractionService.STATE_SUPPRESSED, result.state());
        assertNull(result.eventId());
        assertEquals(0, outboxCountForVid(), "被抑制的请求不得产生统计事件");
        assertEquals(before, windowRow(actorHash, "PLAY").get("last_counted_at"),
                "抑制不推进窗口：last_counted_at 必须原样不变");
        assertEquals(2, intOf(requestRow(result.requestKey()).get("state")));
    }

    @Test
    void requestJustOutsideWindowCountsAgainAndAdvancesWindow() {
        String actorHash = randomActorHash();
        seedWindow(actorHash, "PLAY", -1801);
        Object before = windowRow(actorHash, "PLAY").get("last_counted_at");

        VideoInteractionResult result = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, randomRequestKey()));

        assertTrue(result.counted());
        assertEquals(1, outboxCountForVid());
        Object after = windowRow(actorHash, "PLAY").get("last_counted_at");
        assertNotEquals(before, after, "获准计数必须把窗口推进到本次 DB 时间");
        assertTrue(((java.time.LocalDateTime) after).isAfter(
                        (java.time.LocalDateTime) before),
                "窗口只能向后推进");
    }

    @Test
    void suppressedRequestKeyStillReplaysSuppressedAfterWindowExpires() {
        String actorHash = randomActorHash();
        String suppressedKey = randomRequestKey();
        // 先制造一次“窗口内抑制”。
        seedWindow(actorHash, "PLAY", -60);
        VideoInteractionResult first = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, suppressedKey));
        assertFalse(first.counted());
        assertEquals(0, outboxCountForVid());

        // 把窗口时间推到 2 小时前，等价于“30 分钟后再提交同一个旧请求”。
        jdbc.update("UPDATE video_interaction_window "
                + "SET last_counted_at = TIMESTAMPADD(SECOND, -7200, NOW(3)) "
                + "WHERE actor_hash = ? AND action = 'PLAY'", actorHash);

        VideoInteractionResult replay = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, suppressedKey));

        assertFalse(replay.counted(), "旧请求必须重放历史结果，不能因为窗口过期就重新计数");
        assertEquals(VideoInteractionService.STATE_SUPPRESSED, replay.state());
        assertTrue(replay.duplicate());
        assertEquals(0, outboxCountForVid());

        // 而一个“新”请求此时确实可以计数，说明窗口本身已经放开。
        VideoInteractionResult fresh = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, randomRequestKey()));
        assertTrue(fresh.counted());
        assertEquals(1, outboxCountForVid());
    }

    // -------------------------------------------------------------- concurrency

    @Test
    void concurrentSameRequestKeyCountsExactlyOnce() throws Exception {
        String actorHash = randomActorHash();
        VideoInteractionCommand command = new VideoInteractionCommand(
                VideoInteractionActorType.USER, actorHash, vid,
                VideoInteractionAction.SHARE, randomRequestKey());

        VideoInteractionResult[] results = runConcurrently(
                () -> interactionService.record(command),
                () -> interactionService.record(command));

        assertEquals(1, countRequestRows(command.requestKey()),
                "同一 requestKey 只能有一行请求记录");
        assertTrue(results[0].counted());
        assertTrue(results[1].counted(), "重放必须返回赢家的 COUNTED 结果");
        assertEquals(results[0].eventId(), results[1].eventId(),
                "两个调用必须指向同一条统计事件");
        assertTrue(results[0].duplicate() ^ results[1].duplicate(),
                "恰好一个调用是幂等重放");
        assertEquals(1, outboxCountForVid(), "同一 requestKey 并发只能计一次");
    }

    @Test
    void concurrentDifferentRequestKeysInSameWindowCountOnlyOnce() throws Exception {
        String actorHash = randomActorHash();
        String keyA = randomRequestKey();
        String keyB = randomRequestKey();

        VideoInteractionResult[] results = runConcurrently(
                () -> interactionService.record(new VideoInteractionCommand(
                        VideoInteractionActorType.ANONYMOUS, actorHash, vid,
                        VideoInteractionAction.PLAY, keyA)),
                () -> interactionService.record(new VideoInteractionCommand(
                        VideoInteractionActorType.ANONYMOUS, actorHash, vid,
                        VideoInteractionAction.PLAY, keyB)));

        assertEquals(1, outboxCountForVid(),
                "同一 actor+vid+action 的 30 分钟窗口只能计一次");
        long counted = java.util.Arrays.stream(results).filter(VideoInteractionResult::counted).count();
        assertEquals(1, counted);
        assertEquals(1, countRequestRows(keyA));
        assertEquals(1, countRequestRows(keyB));
    }

    // ------------------------------------------------------ real transaction rollback

    @Test
    void outboxInsertFailureRollsBackRequestAndWindowAndRetryStillCounts() {
        String actorHash = randomActorHash();
        String requestKey = randomRequestKey();
        // 真实故障注入：Outbox 插入被数据库触发器拒绝（等价于事件写入失败）。
        jdbc.execute("CREATE TRIGGER " + FAULT_TRIGGER
                + " BEFORE INSERT ON video_status_outbox FOR EACH ROW"
                + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't15 injected outbox failure'");
        try {
            assertThrows(DataAccessException.class, () -> interactionService.record(
                    new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                            VideoInteractionAction.PLAY, requestKey)));
        } finally {
            dropFaultTrigger();
        }

        assertEquals(0, countRequestRows(requestKey),
                "事件写入失败必须把请求占位一起回滚，不能留下 PENDING 行");
        // 窗口行本身也在这个事务里插入，所以回滚后连行都不该存在：
        // 下一次请求会重新以 last_counted_at=NULL 建行，不会被这次失败吃掉窗口。
        assertEquals(0, windowRowCount(actorHash, "PLAY"),
                "事件写入失败必须把窗口行/推进一起回滚");
        assertEquals(0, outboxCountForVid());

        // 故障移除后同一 requestKey 仍然可以“首次计数”，说明回滚没有留下占位。
        VideoInteractionResult retry = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, requestKey));
        assertTrue(retry.counted());
        assertEquals(1, outboxCountForVid());
        assertNotNull(windowRow(actorHash, "PLAY").get("last_counted_at"));
    }

    // -------------------------------------------------------------------- helpers

    private VideoInteractionResult[] runConcurrently(
            ThrowingSupplier<VideoInteractionResult> first,
            ThrowingSupplier<VideoInteractionResult> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<VideoInteractionResult> a = pool.submit(() -> {
                start.await();
                return first.get();
            });
            Future<VideoInteractionResult> b = pool.submit(() -> {
                start.await();
                return second.get();
            });
            start.countDown();
            return new VideoInteractionResult[]{
                    a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
        }
    }

    private void seedWindow(String actorHash, String action, int offsetSeconds) {
        jdbc.update("INSERT INTO video_interaction_window"
                        + "(actor_type, actor_hash, vid, action, last_counted_at) "
                        + "VALUES ('USER', ?, ?, ?, TIMESTAMPADD(SECOND, ?, NOW(3)))",
                actorHash, vid, action, offsetSeconds);
    }

    private Map<String, Object> requestRow(String requestKey) {
        return jdbc.queryForMap(
                "SELECT request_key, actor_type, actor_hash, vid, action, state, event_id, "
                        + "created_at, completed_at FROM video_interaction_request "
                        + "WHERE request_key = ?", requestKey);
    }

    private Map<String, Object> windowRow(String actorHash, String action) {
        return jdbc.queryForMap(
                "SELECT id, actor_type, actor_hash, vid, action, last_counted_at "
                        + "FROM video_interaction_window WHERE actor_hash = ? AND vid = ? "
                        + "AND action = ?", actorHash, vid, action);
    }

    private int windowRowCount(String actorHash, String action) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_interaction_window "
                        + "WHERE actor_hash = ? AND vid = ? AND action = ?",
                Integer.class, actorHash, vid, action);
    }

    private int countRequestRows(String requestKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_interaction_request WHERE request_key = ?",
                Integer.class, requestKey);
    }

    private int outboxCountForVid() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_outbox WHERE aggregate_id = ?",
                Integer.class, vid);
    }

    private int outboxCountForEvent(String eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_outbox WHERE event_id = ?",
                Integer.class, eventId);
    }

    private long playTimes() {
        return jdbc.queryForObject(
                "SELECT play_times FROM video_status WHERE vid = ?", Long.class, vid);
    }

    private void dropFaultTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS " + FAULT_TRIGGER);
    }

    private static int intOf(Object value) {
        return ((Number) value).intValue();
    }

    private static String randomActorHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString())
                .replace("-", "");
    }

    private static String randomRequestKey() {
        return "t15-" + UUID.randomUUID();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
