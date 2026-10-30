package com.feibijiubi.backend.interaction.service;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.RedisScriptConfig;
import com.feibijiubi.backend.interaction.config.VideoInteractionProperties;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionActorResolver;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionAdmissionServiceImpl;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionRateLimitServiceImpl;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionServiceImpl;
import com.feibijiubi.backend.video.service.PublishedVideoGuard;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusService;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.interaction.vo.VideoInteractionVO;
import com.feibijiubi.backend.service.video.VideoStatusIsolatedIntegrationTests;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T15 第二阶段接线（HTTP 入口 → 参数/视频校验 → 重放查询 → Redis 准入 → DB 核心）的真实隔离 IT：
 * MySQL 13307 + Redis 16379，只允许 {@code compose.test.yaml} 的端点。
 *
 * <p>只在显式 JVM 参数 {@code -DvideoStatusIT=true} 时运行，复用
 * {@link VideoStatusIsolatedIntegrationTests.IsolationGuard}，不加载 application.yml、不连正式环境、
 * 不清库。每个用例只新建自己的 user/video/video_status 行与随机访客标识，只删除自己创建的
 * 限流键（{@code rate:video-interaction:*}）。</p>
 *
 * <h2>覆盖的不变量</h2>
 * <ol>
 *   <li>新请求先过 Redis 准入，再进 DB：第一次计数、同访客第二次被 30 分钟窗口抑制；</li>
 *   <li>准入额度真实生效（固定窗口脚本），超限返回 429 且<b>不产生任何 DB 请求行</b>；</li>
 *   <li>同一 {@code requestKey} 的重放在额度耗尽后仍直接返回历史结果（Redis 完全不参与），
 *       即使窗口被回拨到 30 分钟之前也不会重新判定；</li>
 *   <li>“同一 actor 合计”维度能拦住“每个动作各刷一份额度”；</li>
 *   <li>匿名访客与登录用户是两个身份空间，且登录态忽略 {@code X-Visitor-Id}（非法值也不报错）；</li>
 *   <li>参数/视频校验失败时不会创建任何限流键（顺序契约：校验先于 Redis）。</li>
 * </ol>
 *
 * <p>运行：{@code ./mvnw -DvideoStatusIT=true -Dtest=VideoInteractionAdmissionIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoInteractionAdmissionIsolatedTests.TestApplication.class,
        properties = {
                "spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none",
                "app.video-status.scheduling-enabled=false",
                "app.video-status.outbox-relay-enabled=false",
                // 同步计数路径：计数直接落 video_status，用例可以直接断言次数。
                "app.video-status.async-enabled=false"
        })
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VideoInteractionAdmissionIsolatedTests {

    private static final String CANONICAL_VISITOR = "3f2a9c74-0f39-4ccc-9f24-08a4c1f3f2a1";

    @Configuration(proxyBeanMethods = false)
    @Profile("video-status-isolated")
    @EnableAutoConfiguration(excludeName =
            "org.redisson.spring.starter.RedissonAutoConfigurationV2")
    @EnableConfigurationProperties({VideoStatusProperties.class, VideoInteractionProperties.class})
    // R3：交互/视频 mapper 已按模块迁走，旧扫描范围保持不变，只追加新包。
    @MapperScan({"com.feibijiubi.backend.mapper", "com.feibijiubi.backend.video.mapper",
            "com.feibijiubi.backend.interaction.mapper"})
    @Import({RedisScriptConfig.class,
            PublishedVideoGuard.class,
            VideoInteractionServiceImpl.class,
            VideoInteractionActorResolver.class,
            VideoInteractionRateLimitServiceImpl.class,
            VideoInteractionAdmissionServiceImpl.class,
            RedisUtils.class, JsonUtils.class})
    static class TestApplication {
        /** 同步路径不得创建统计事件：本类只验证准入 + 同步计数。 */
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
    @Autowired StringRedisTemplate redis;
    @Autowired VideoInteractionAdmissionService admissionService;
    @Autowired VideoInteractionActorResolver actorResolver;
    @Autowired VideoInteractionProperties interactionProperties;
    @Autowired VideoStatusProperties videoStatusProperties;

    int vid;
    int uid;
    final List<String> usedActorHashes = new ArrayList<>();

    @BeforeEach
    void seedOwnRows() {
        assertFalse(videoStatusProperties.isAsyncEnabled(),
                "本类验证同步计数路径，隔离配置必须是 async-enabled=false");
        String username = "t15-adm-it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) "
                + "VALUES (?, 'test', 'test')", username);
        uid = jdbc.queryForObject(
                "SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,"
                + "video_key,duration,status) "
                + "VALUES (?, 't15-admission', 'test', 'test', '', '', '', '', 12.5, 1)", uid);
        vid = jdbc.queryForObject(
                "SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times,share_times) VALUES (?,0,0)", vid);
        // 默认宽松额度：只有显式设置的小额度用例才验证 429。
        interactionProperties.setWindowSeconds(60);
        interactionProperties.setMaxRequestsPerAction(10);
        interactionProperties.setMaxRequestsPerActor(10);
    }

    @AfterEach
    void deleteOwnAdmissionKeys() {
        for (String hash : usedActorHashes) {
            redis.delete(List.of(
                    RedisKeyUtils.rateVideoInteractionAction(hash, "PLAY"),
                    RedisKeyUtils.rateVideoInteractionAction(hash, "SHARE"),
                    RedisKeyUtils.rateVideoInteractionActor(hash)));
        }
        usedActorHashes.clear();
    }

    // ------------------------------------------------------------- admission + window

    @Test
    void newRequestCountsThenSameVisitorIsWindowSuppressed() {
        String visitor = randomVisitor();
        String hash = trackVisitor(visitor);

        VideoInteractionVO first = play(visitor, randomKey());
        assertTrue(first.counted());
        assertFalse(first.duplicate());
        assertEquals(VideoInteractionVO.REASON_COUNTED, first.reason());
        assertEquals(1L, playTimes());

        VideoInteractionVO second = play(visitor, randomKey());
        assertFalse(second.counted());
        assertFalse(second.duplicate(), "新 requestKey 不是幂等重放，而是被窗口抑制");
        assertEquals(VideoInteractionVO.REASON_WINDOW_SUPPRESSED, second.reason());
        assertEquals(1L, playTimes(), "抑制不计数");

        assertEquals(2, requestRows(), "两次请求都留下了终态行（一次 COUNTED、一次 SUPPRESSED）");

        // 准入键只含 actor 哈希，且带窗口 TTL。
        Long actionTtl = redis.getExpire(RedisKeyUtils.rateVideoInteractionAction(hash, "PLAY"));
        Long actorTtl = redis.getExpire(RedisKeyUtils.rateVideoInteractionActor(hash));
        assertNotNull(actionTtl);
        assertNotNull(actorTtl);
        assertTrue(actionTtl > 0 && actionTtl <= 60, "单动作维度必须有窗口 TTL: " + actionTtl);
        assertTrue(actorTtl > 0 && actorTtl <= 60, "合计维度必须有窗口 TTL: " + actorTtl);
        assertFalse(RedisKeyUtils.rateVideoInteractionAction(hash, "PLAY").contains(visitor),
                "限流键不得包含访客原文");
    }

    @Test
    void overLimitRejectsNewRequestWithNoDatabaseRow() {
        interactionProperties.setMaxRequestsPerAction(1);
        interactionProperties.setMaxRequestsPerActor(2);
        String visitor = randomVisitor();
        trackVisitor(visitor);

        assertTrue(play(visitor, randomKey()).counted());
        assertEquals(1, requestRows());

        BusinessException error = assertThrows(BusinessException.class,
                () -> play(visitor, randomKey()));
        assertEquals(429, error.getCode());
        assertEquals(1, requestRows(), "超限请求不得写入任何请求行");
        assertEquals(1L, playTimes(), "超限请求不得计数");
    }

    // --------------------------------------------------------------- replay bypasses redis

    @Test
    void replayOfCommittedRequestBypassesExhaustedQuotaAndWindowExpiry() {
        interactionProperties.setMaxRequestsPerAction(2);
        interactionProperties.setMaxRequestsPerActor(3);
        String visitor = randomVisitor();
        String hash = trackVisitor(visitor);
        String countedKey = randomKey();

        assertTrue(play(visitor, countedKey).counted());
        assertFalse(play(visitor, randomKey()).counted());
        // 第三次新请求把单动作维度打到 3 > 2。
        assertEquals(429, assertThrows(BusinessException.class,
                () -> play(visitor, randomKey())).getCode());
        assertEquals(2, requestRows());

        // 把窗口回拨到 30 分钟之前：模拟“很久以后又提交同一个旧请求”。
        jdbc.update("UPDATE video_interaction_window "
                        + "SET last_counted_at = TIMESTAMPADD(SECOND, -7200, NOW(3)) "
                        + "WHERE actor_hash = ? AND action = 'PLAY' AND vid = ?",
                hash, vid);

        VideoInteractionVO replay = play(visitor, countedKey);

        assertTrue(replay.counted(), "重放必须返回原始结果（原请求计过数）");
        assertTrue(replay.duplicate(), "命中已存在的 requestKey 必须是重放");
        assertEquals(2, requestRows(), "重放不得新增请求行，也不受额度影响");

        // 额度仍然耗尽：新请求依旧 429，证明“去重”和“准入”互不替代。
        assertEquals(429, assertThrows(BusinessException.class,
                () -> play(visitor, randomKey())).getCode());
        assertEquals(1L, playTimes());
    }

    // ----------------------------------------------------------- two rate-limit dimensions

    @Test
    void actorTotalDimensionRejectsEvenWhenEachActionIsUnderItsOwnLimit() {
        interactionProperties.setMaxRequestsPerAction(3);
        interactionProperties.setMaxRequestsPerActor(3);
        String visitor = randomVisitor();
        trackVisitor(visitor);

        assertTrue(play(visitor, randomKey()).counted());
        assertTrue(share(visitor, randomKey()).counted());
        assertFalse(play(visitor, randomKey()).counted());
        assertEquals(3, requestRows());

        // 合计维度已到 4 > 3：SHARE 自己的单动作维度只有 2，仍然必须被拒绝。
        assertEquals(429, assertThrows(BusinessException.class,
                () -> share(visitor, randomKey())).getCode());
        assertEquals(3, requestRows());
        assertEquals(1L, playTimes());
        assertEquals(1L, shareTimes());
    }

    // ------------------------------------------------------------------------- identity

    @Test
    void anonymousAndLoggedInAreSeparateActorsAndLoginIgnoresVisitorHeader() {
        String visitor = randomVisitor();
        trackVisitor(visitor);
        trackLoggedIn(uid);

        assertTrue(play(visitor, randomKey()).counted());
        assertFalse(play(visitor, randomKey()).counted(), "同一匿名访客在窗口内只计一次");

        // 登录身份优先：非法 visitor 头既不能让它 400，也不能让它变成第二个匿名身份。
        VideoInteractionVO loggedIn = admissionService.record(VideoInteractionAction.PLAY, uid, vid,
                randomKey(), "not-a-uuid");
        assertTrue(loggedIn.counted());
        assertFalse(loggedIn.duplicate());

        assertEquals(2L, playTimes(), "匿名一次 + 登录一次");
    }

    // ------------------------------------------------------- validation happens before redis

    @Test
    void rejectedInputsNeverCreateAdmissionKeys() {
        String visitor = randomVisitor();
        String hash = trackVisitor(visitor);

        assertEquals(400, assertThrows(BusinessException.class,
                () -> admissionService.record(VideoInteractionAction.PLAY, null, vid,
                        null, visitor)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> admissionService.record(VideoInteractionAction.PLAY, null, vid,
                        "bad key", visitor)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> admissionService.record(VideoInteractionAction.PLAY, null, vid,
                        randomKey(), "not-a-uuid")).getCode());
        // 视频不存在（未过审/已删除）同样在限流之前失败。
        assertEquals(404, assertThrows(BusinessException.class,
                () -> admissionService.record(VideoInteractionAction.PLAY, null, 999_999_998,
                        randomKey(), visitor)).getCode());

        assertFalse(hasKey(RedisKeyUtils.rateVideoInteractionAction(hash, "PLAY")),
                "参数/视频校验失败时不得消耗准入额度");
        assertFalse(hasKey(RedisKeyUtils.rateVideoInteractionActor(hash)));
        assertEquals(0, requestRows());
        assertEquals(0L, playTimes());
    }

    // -------------------------------------------------------------------- helpers

    private VideoInteractionVO play(String visitor, String requestKey) {
        return admissionService.record(VideoInteractionAction.PLAY, null, vid, requestKey, visitor);
    }

    private VideoInteractionVO share(String visitor, String requestKey) {
        return admissionService.record(VideoInteractionAction.SHARE, null, vid, requestKey, visitor);
    }

    private String trackVisitor(String visitor) {
        return trackActorHash(actorResolver.resolve(null, visitor).hash());
    }

    private String trackLoggedIn(int loggedInUid) {
        return trackActorHash(actorResolver.resolve(loggedInUid, null).hash());
    }

    private String trackActorHash(String actorHash) {
        usedActorHashes.add(actorHash);
        return actorHash;
    }

    private boolean hasKey(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    private String randomVisitor() {
        return UUID.randomUUID().toString();
    }

    private String randomKey() {
        return UUID.randomUUID().toString();
    }

    private long playTimes() {
        return jdbc.queryForObject(
                "SELECT play_times FROM video_status WHERE vid=?", Long.class, vid);
    }

    private long shareTimes() {
        return jdbc.queryForObject(
                "SELECT share_times FROM video_status WHERE vid=?", Long.class, vid);
    }

    private int requestRows() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_interaction_request WHERE vid=?", Integer.class, vid);
    }
}
