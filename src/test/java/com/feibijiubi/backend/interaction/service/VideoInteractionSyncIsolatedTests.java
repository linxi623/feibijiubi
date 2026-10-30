package com.feibijiubi.backend.interaction.service;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusService;
import com.feibijiubi.backend.service.video.VideoStatusIsolatedIntegrationTests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T15 第一阶段（同步计数路径）的真实隔离 IT：只连 MySQL 13307，不连 Redis/MQ。
 *
 * <p>只在显式 JVM 参数 {@code -DvideoStatusIT=true} 时运行，复用
 * {@link VideoStatusIsolatedIntegrationTests.IsolationGuard}。本类把
 * {@code app.video-status.async-enabled} 覆盖为 {@code false}，并用一个<b>一旦被调用就失败</b>的
 * {@link VideoStatusService} 桩证明同步路径绝不产生 MQ 事件。</p>
 *
 * <h2>覆盖的不变量</h2>
 * <ol>
 *   <li>同步路径走既有守卫更新（{@code increasePlayTimes} / {@code increaseShareTimes}），
 *       计数一次、{@code event_id} 为 NULL、不产生 Outbox 行。</li>
 *   <li>窗口内抑制不改任何计数、不推进窗口。</li>
 *   <li>计数失败（{@code video_status.play_times} 已达 Long.MAX，守卫更新 0 行）时，
 *       请求占位与窗口推进<b>整事务回滚</b>：无请求行、窗口 {@code last_counted_at} 仍为 NULL、
 *       计数不变。这是 Mockito 无法证明的真实回滚。</li>
 *   <li>同一 requestKey 复用到不同 vid 是形状冲突（409），且不额外计数。</li>
 * </ol>
 *
 * <p>运行：{@code ./mvnw -DvideoStatusIT=true -Dtest=VideoInteractionSyncIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoInteractionSyncIsolatedTests.TestApplication.class,
        properties = {
                "spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none",
                "app.video-status.scheduling-enabled=false",
                "app.video-status.outbox-relay-enabled=false",
                // 本类验证同步计数路径。
                "app.video-status.async-enabled=false"
        })
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VideoInteractionSyncIsolatedTests {

    private static final long LONG_MAX = Long.MAX_VALUE;

    @Configuration(proxyBeanMethods = false)
    @Profile("video-status-isolated")
    @EnableAutoConfiguration(excludeName =
            "org.redisson.spring.starter.RedissonAutoConfigurationV2")
    @EnableConfigurationProperties(VideoStatusProperties.class)
    // R3：交互 mapper 已迁到 com.feibijiubi.backend.interaction.mapper，旧扫描范围保持不变，只追加新包。
    @MapperScan({"com.feibijiubi.backend.mapper", "com.feibijiubi.backend.interaction.mapper"})
    @Import(VideoInteractionServiceImpl.class)
    static class TestApplication {
        /**
         * 同步路径不得创建统计事件。任何调用都是本类要抓的缺陷，因此直接抛错而不是返回 null。
         */
        @Bean
        VideoStatusService unusedVideoStatusService() {
            return new VideoStatusService() {
                @Override
                public ApplyResult apply(VideoStatusChangedEvent event) {
                    throw new AssertionError("同步计数路径不得调用 apply()");
                }

                @Override
                public VideoStatusChangedEvent createEvent(Integer vid, VideoStatusEventType type,
                                                           long delta) {
                    throw new AssertionError("同步计数路径不得创建统计事件");
                }

                @Override
                public VideoStatus getByVid(Integer vid) {
                    throw new AssertionError("同步计数路径不得读取 Redis 统计");
                }
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired VideoInteractionService interactionService;
    @Autowired VideoStatusProperties properties;

    int vid;
    int otherVid;

    @BeforeEach
    void seedOwnVideos() {
        assertFalse(properties.isAsyncEnabled(),
                "本类验证同步计数路径，隔离配置必须是 async-enabled=false");
        String username = "t15-sync-it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) "
                + "VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject(
                "SELECT id FROM users WHERE username=?", Integer.class, username);
        vid = seedVideo(uid, "t15-sync-a");
        otherVid = seedVideo(uid, "t15-sync-b");
        jdbc.update("INSERT INTO video_status(vid,play_times,share_times) VALUES (?,0,0)", vid);
        jdbc.update("INSERT INTO video_status(vid,play_times,share_times) VALUES (?,0,0)", otherVid);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void syncPlayCountsOnceThroughGuardedUpdateWithoutEvent() {
        String actorHash = randomActorHash();
        String requestKey = randomRequestKey();

        VideoInteractionResult result = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, requestKey));

        assertTrue(result.counted());
        assertEquals(VideoInteractionService.STATE_COUNTED, result.state());
        assertNull(result.eventId(), "同步路径不产生 MQ 事件，event_id 必须为 NULL");
        assertEquals(1L, playTimes());
        assertEquals(0L, shareTimes());
        assertEquals(0, outboxCountForVid());
        Map<String, Object> request = requestRow(requestKey);
        assertEquals(1, intOf(request.get("state")));
        assertNull(request.get("event_id"));
        assertNotNull(windowRow(actorHash, "PLAY").get("last_counted_at"));
    }

    @Test
    void syncShareCountsThroughShareGuardedUpdate() {
        String actorHash = randomActorHash();

        VideoInteractionResult result = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.ANONYMOUS, actorHash, vid,
                        VideoInteractionAction.SHARE, randomRequestKey()));

        assertTrue(result.counted());
        assertEquals(1L, shareTimes());
        assertEquals(0L, playTimes());
        assertEquals(0, outboxCountForVid());
    }

    @Test
    void syncWindowSuppressionTouchesNothing() {
        String actorHash = randomActorHash();
        jdbc.update("INSERT INTO video_interaction_window"
                        + "(actor_type, actor_hash, vid, action, last_counted_at) "
                        + "VALUES ('USER', ?, ?, 'PLAY', TIMESTAMPADD(SECOND, -60, NOW(3)))",
                actorHash, vid);
        Object before = windowRow(actorHash, "PLAY").get("last_counted_at");

        VideoInteractionResult result = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, randomRequestKey()));

        assertFalse(result.counted());
        assertEquals(0L, playTimes());
        assertEquals(before, windowRow(actorHash, "PLAY").get("last_counted_at"));
    }

    // ------------------------------------------------------ real transaction rollback

    @Test
    void syncCountFailureAtLongMaxRollsBackRequestAndWindow() {
        jdbc.update("UPDATE video_status SET play_times = ? WHERE vid = ?", LONG_MAX, vid);
        String actorHash = randomActorHash();
        String requestKey = randomRequestKey();

        BusinessException error = assertThrows(BusinessException.class,
                () -> interactionService.record(new VideoInteractionCommand(
                        VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, requestKey)));

        assertEquals(500, error.getCode());
        assertEquals(0, countRequestRows(requestKey),
                "计数失败必须把请求占位一起回滚，不能留下 PENDING 行");
        // 窗口行本身就是在同一事务里插入的，所以回滚后连行都不该存在：
        // 下一次请求会重新以 last_counted_at=NULL 建行，不会被这次的失败吃掉窗口。
        assertEquals(0, windowRowCount(actorHash, "PLAY"),
                "计数失败必须把窗口行/推进一起回滚");
        assertEquals(LONG_MAX, playTimes());

        // 窗口没被吃掉：修好计数条件后，同一 actor 的“新请求”仍能立刻计数。
        jdbc.update("UPDATE video_status SET play_times = 0 WHERE vid = ?", vid);
        VideoInteractionResult retry = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, randomRequestKey()));
        assertTrue(retry.counted());
        assertEquals(1L, playTimes());
    }

    // -------------------------------------------------------------- idempotency

    @Test
    void sameRequestKeyReusedForDifferentVidIsConflictAndDoesNotCountAgain() {
        String actorHash = randomActorHash();
        String requestKey = randomRequestKey();
        VideoInteractionResult first = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.PLAY, requestKey));
        assertTrue(first.counted());

        BusinessException error = assertThrows(BusinessException.class,
                () -> interactionService.record(new VideoInteractionCommand(
                        VideoInteractionActorType.USER, actorHash, otherVid,
                        VideoInteractionAction.PLAY, requestKey)));

        assertEquals(409, error.getCode());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT play_times FROM video_status WHERE vid = ?", Long.class, otherVid));
        assertEquals(1L, playTimes());
    }

    @Test
    void requestKeyIsCaseSensitiveSoDifferentCaseIsADifferentRequest() {
        String actorHash = randomActorHash();
        String lowerKey = "t15-sync-case-" + UUID.randomUUID();
        String upperKey = lowerKey.toUpperCase(java.util.Locale.ROOT);

        VideoInteractionResult first = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.SHARE, lowerKey));
        VideoInteractionResult second = interactionService.record(
                new VideoInteractionCommand(VideoInteractionActorType.USER, actorHash, vid,
                        VideoInteractionAction.SHARE, upperKey));

        assertTrue(first.counted());
        // 若 request_key 是大小写不敏感的唯一键，第二个请求会撞唯一键并被当作 first 的重放；
        // 大小写敏感时它是另一个独立请求，落在同一 30 分钟窗口里所以被抑制。
        assertFalse(second.duplicate(), "不同大小写的 requestKey 必须是两个不同请求");
        assertFalse(second.counted());
        assertEquals(1, countRequestRows(lowerKey));
        assertEquals(1, countRequestRows(upperKey));
        assertEquals(1L, shareTimes());
    }

    @Test
    void replayReturnsStoredCountedResultWithoutCountingAgain() {
        String actorHash = randomActorHash();
        String requestKey = randomRequestKey();
        VideoInteractionCommand command = new VideoInteractionCommand(
                VideoInteractionActorType.USER, actorHash, vid,
                VideoInteractionAction.PLAY, requestKey);

        VideoInteractionResult first = interactionService.record(command);
        VideoInteractionResult replay = interactionService.record(command);

        assertTrue(first.counted());
        assertFalse(first.duplicate());
        assertTrue(replay.counted());
        assertTrue(replay.duplicate());
        assertEquals(first.requestId(), replay.requestId());
        assertEquals(1L, playTimes(), "重放不得再计一次");
    }

    // -------------------------------------------------------------------- helpers

    private int seedVideo(int uid, String title) {
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, ?, 'test', 'test', '', '', '', '')", uid, title);
        return jdbc.queryForObject(
                "SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
    }

    /** play_times / share_times 是 BIGINT UNSIGNED，必须用 Long.class 转换，不能直接取 Map 值。 */
    private long playTimes() {
        return jdbc.queryForObject(
                "SELECT play_times FROM video_status WHERE vid = ?", Long.class, vid);
    }

    private long shareTimes() {
        return jdbc.queryForObject(
                "SELECT share_times FROM video_status WHERE vid = ?", Long.class, vid);
    }

    private Map<String, Object> requestRow(String requestKey) {
        return jdbc.queryForMap(
                "SELECT request_key, state, event_id, completed_at "
                        + "FROM video_interaction_request WHERE request_key = ?", requestKey);
    }

    private Map<String, Object> windowRow(String actorHash, String action) {
        return jdbc.queryForMap(
                "SELECT id, last_counted_at FROM video_interaction_window "
                        + "WHERE actor_hash = ? AND vid = ? AND action = ?",
                actorHash, vid, action);
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

    private static int intOf(Object value) {
        return ((Number) value).intValue();
    }

    private static String randomActorHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString())
                .replace("-", "");
    }

    private static String randomRequestKey() {
        return "t15-sync-" + UUID.randomUUID();
    }
}
