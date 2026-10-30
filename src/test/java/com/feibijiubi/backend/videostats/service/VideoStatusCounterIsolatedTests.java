package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.video.converter.VideoConverter;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.videostats.entity.VideoStatus;
import com.feibijiubi.backend.event.VideoStatusDelta;
import com.feibijiubi.backend.videostats.mapper.VideoStatusMapper;
import com.feibijiubi.backend.infrastructure.redis.RedisKeyUtils;
import com.feibijiubi.backend.video.vo.VideoDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T02 isolated regression: DB -> Java -> VO and Redis -> exact Long counters.
 *
 * <p>Only the {@code compose.test.yaml} endpoints are allowed (MySQL 13307,
 * Redis 16379). RabbitMQ is excluded. Every test creates its own DB row and its own
 * Redis vid, and removes only those keys / dirty / hot members afterwards.</p>
 *
 * <p>增量用例遵守真实协议：{@code init} 成功只写 Hash，门闩由 release-gate 脚本
 * 以 owner token 释放，之后增量才可能 {@code APPLIED}；每个事件使用独立 eventId，
 * 同一 eventId 的重放必须是 {@code DUPLICATE}，而新事件必须再应用一次。</p>
 *
 * <p>运行：{@code ./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusCounterIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusCounterIsolatedTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusCounterIsolatedTests.IsolationGuard.class)
class VideoStatusCounterIsolatedTests {

    /**
     * Reuses {@link VideoStatusSqlBaselineTests.IsolationGuard} verbatim for the datasource
     * (exact JDBC URL, credentials, and every hikari/jndi/driver override rejected), then adds the
     * Redis side: host/port must be the isolated instance and every other
     * {@code spring.data.redis.*} key is refused, so {@code redis.url}, cluster or sentinel
     * configuration cannot route the test to another server.
     *
     * <p>Note: the Redis side rebuilds the guard from a fresh instance each time.
     */
    static class IsolationGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        private static final List<String> FORBIDDEN_EXPLICIT_REDIS_KEYS = List.of(
                "spring.data.redis.url",
                "spring.data.redis.hostname",
                "spring.data.redis.socket",
                "spring.data.redis.cluster.nodes",
                "spring.data.redis.sentinel.master",
                "spring.data.redis.sentinel.nodes",
                "spring.data.redis.password",
                // Legacy Boot 2 spelling: silently ignored by Boot 3, but a reviewer must not believe it works.
                "spring.redis.url",
                "spring.redis.host",
                "spring.redis.port",
                "spring.redis.cluster.nodes",
                "spring.redis.sentinel.master");

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            new VideoStatusSqlBaselineTests.IsolationGuard().initialize(context);

            var env = context.getEnvironment();
            if (!"127.0.0.1".equals(env.getProperty("spring.data.redis.host"))
                    || !"16379".equals(env.getProperty("spring.data.redis.port"))) {
                throw new IllegalStateException("Refusing non-isolated Redis endpoint");
            }
            for (String key : FORBIDDEN_EXPLICIT_REDIS_KEYS) {
                if (env.containsProperty(key)) {
                    throw new IllegalStateException("Unexpected Redis override: " + key);
                }
            }
            // Whatever the spelling (env-var style included), only host/port may be configured.
            for (var propertySource : env.getPropertySources()) {
                if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String name : enumerable.getPropertyNames()) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    for (String candidate : List.of(lower, lower.replace('_', '.'))) {
                        if (!candidate.startsWith("spring.data.redis")) {
                            continue;
                        }
                        if (!"spring.data.redis.host".equals(candidate)
                                && !"spring.data.redis.port".equals(candidate)) {
                            throw new IllegalStateException("Unexpected Redis override: " + name);
                        }
                    }
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
    @MapperScan("com.feibijiubi.backend.videostats.mapper")
    static class TestApplication {
    }

    private static final long THREE_BILLION = 3_000_000_000L;
    private static final long TWO_POW_53_PLUS_ONE = 9_007_199_254_740_993L;
    private static final String INIT_HOT_SCORE = "10.5";
    private static final String OWNER_TOKEN = "t02-token";

    private static final RedisScript<String> INIT = RedisScript.of(
            new ClassPathResource("lua/video-status-init.lua"), String.class);
    private static final RedisScript<String> INCREMENT = RedisScript.of(
            new ClassPathResource("lua/video-status-increment.lua"), String.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of(
            new ClassPathResource("lua/video-status-release-gate.lua"), Long.class);

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    VideoStatusMapper mapper;
    @Autowired
    StringRedisTemplate redis;

    int vid;
    int redisVid;
    String eventId;

    @BeforeEach
    void seed() {
        String username = "t02-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 't02', 'test', 'test', '', '', '', '')", uid);
        vid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid) VALUES (?)", vid);
        redisVid = vid + 1_000_000;
        eventId = "t02-" + UUID.randomUUID();
        purgeOwnRedisState();
    }

    @AfterEach
    void cleanupRedis() {
        purgeOwnRedisState();
    }

    // ---------------------------------------------------------------- redis keys

    private String currentKey() {
        return RedisKeyUtils.videoStatus(redisVid);
    }

    private String gateKey() {
        return RedisKeyUtils.videoStatusRebuildGate(redisVid);
    }

    private String member() {
        return String.valueOf(redisVid);
    }

    private Double hotScore() {
        return redis.opsForZSet().score(RedisKeyUtils.feedHotVideos(), member());
    }

    private boolean isDirty() {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(RedisKeyUtils.dirtyVideo(), member()));
    }

    private String hash(String field) {
        return (String) redis.opsForHash().get(currentKey(), field);
    }

    private Long processedTtl(String event) {
        return redis.getExpire(RedisKeyUtils.processedKey(event));
    }

    /** Removes only this test's own vid keys, dirty member, hot member and the given event key. */
    private void purgeOwnRedisState() {
        redis.delete(List.of(currentKey(), gateKey(), RedisKeyUtils.processedKey(eventId)));
        redis.opsForSet().remove(RedisKeyUtils.dirtyVideo(), member());
        redis.opsForZSet().remove(RedisKeyUtils.feedHotVideos(), member());
    }

    /** A new event is a new id: reusing one id on purpose is the duplicate test. */
    private String nextEvent() {
        eventId = "t02-" + UUID.randomUUID();
        return eventId;
    }

    // -------------------------------------------------------------------- DB side

    private void setPlay(long value) {
        jdbc.update("UPDATE video_status SET play_times = ? WHERE vid = ?", value, vid);
    }

    private BigDecimal play() {
        return jdbc.queryForObject("SELECT play_times FROM video_status WHERE vid = ?", BigDecimal.class, vid);
    }

    private VideoStatusDelta playDelta(long delta) {
        return new VideoStatusDelta(delta, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void dbToJavaToVoKeepsThreeBillion() {
        setPlay(THREE_BILLION);
        VideoStatus status = mapper.selectByVid(vid);
        assertEquals(THREE_BILLION, status.getPlayTimes());

        Video video = new Video();
        video.setVid(vid);
        VideoDetailVO vo = VideoConverter.toVideoDetailVO(video, status, null, null, null, null, null);
        assertEquals(THREE_BILLION, vo.getPlayTimes());
    }

    @Test
    void dbToJavaKeepsLongMax() {
        setPlay(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, mapper.selectByVid(vid).getPlayTimes());
        assertEquals(Long.MAX_VALUE,
                jdbc.queryForObject("SELECT play_times FROM video_status WHERE vid = ?", Long.class, vid));
    }

    @Test
    void zeroPlusNegativeOneReturnsZeroRowsWithoutMutationOrError() {
        setPlay(0);
        assertEquals(0, mapper.applyBatchDelta(vid, playDelta(-1)));
        assertEquals(0, BigDecimal.ZERO.compareTo(play()));
    }

    @Test
    void onePlusNegativeOneAndNetZeroBatchSucceed() {
        setPlay(1);
        assertEquals(1, mapper.applyBatchDelta(vid, playDelta(-1)));
        assertEquals(0, BigDecimal.ZERO.compareTo(play()));

        setPlay(5);
        assertEquals(1, mapper.applyBatchDelta(vid, playDelta(0)));
        assertEquals(0, new BigDecimal(5).compareTo(play()));
    }

    @Test
    void multiFieldBatchIsAtomicWhenOneFieldWouldGoNegative() {
        jdbc.update("UPDATE video_status SET play_times = 5, like_times = 0 WHERE vid = ?", vid);
        VideoStatusDelta mixed = new VideoStatusDelta(1, -1, 0, 0, 0, 0, 0, 0);
        assertEquals(0, mapper.applyBatchDelta(vid, mixed));
        assertEquals(5L, mapper.selectByVid(vid).getPlayTimes());
        assertEquals(0L, mapper.selectByVid(vid).getLikeTimes());
    }

    @Test
    void upperBoundIsRejectedWithoutMutation() {
        setPlay(Long.MAX_VALUE);
        assertEquals(0, mapper.applyBatchDelta(vid, playDelta(1)));
        assertEquals(0, new BigDecimal("9223372036854775807").compareTo(play()));
    }

    @Test
    void syncDecreaseReturnsZeroRowsAtZeroInsteadOfUnsignedUnderflow() {
        jdbc.update("UPDATE video_status SET like_times = 0, collect_times = 0 WHERE vid = ?", vid);
        assertEquals(0, mapper.decreaseLikeTimes(vid, true));
        assertEquals(0, mapper.decreaseCollectTimes(vid));
        assertEquals(0L, mapper.selectByVid(vid).getLikeTimes());
        assertEquals(0L, mapper.selectByVid(vid).getCollectTimes());
    }

    @Test
    void syncIncreaseStopsAtLongMax() {
        setPlay(Long.MAX_VALUE);
        assertEquals(0, mapper.increasePlayTimes(vid));
        assertEquals(Long.MAX_VALUE, mapper.selectByVid(vid).getPlayTimes());
    }

    // ------------------------------------------------------------------ Lua side

    private static String[] zerosWithPlay(String playTimes) {
        return new String[]{playTimes, "0", "0", "0", "0", "0", "0", "0"};
    }

    private String executeInit(String[] counters, String hotScore, String hasPending) {
        redis.opsForValue().set(gateKey(), OWNER_TOKEN);
        List<Object> args = new java.util.ArrayList<>(12);
        args.add(String.valueOf(redisVid));
        args.add(hotScore);
        args.addAll(List.of(counters));
        args.add(hasPending);
        args.add(OWNER_TOKEN);
        return redis.execute(INIT,
                List.of(currentKey(), RedisKeyUtils.dirtyVideo(), RedisKeyUtils.feedHotVideos(), gateKey()),
                args.toArray());
    }

    /** init writes the hash only; the real pipeline releases the gate with the release-gate script. */
    private void initReady(String[] counters) {
        assertEquals("INITIALIZED", executeInit(counters, INIT_HOT_SCORE, "false"));
        assertEquals(1L, redis.execute(RELEASE, List.of(gateKey()), OWNER_TOKEN));
        assertEquals(Boolean.FALSE, redis.hasKey(gateKey()), "gate must be released before increments");
    }

    private void initReady() {
        initReady(zerosWithPlay("0"));
    }

    private String increment(String event, String field, String delta) {
        return redis.execute(INCREMENT,
                List.of(currentKey(), RedisKeyUtils.dirtyVideo(), RedisKeyUtils.processedKey(event),
                        RedisKeyUtils.feedHotVideos(), gateKey()),
                field, delta, String.valueOf(redisVid), "1.0", "3600");
    }

    private String increment(String field, String delta) {
        return increment(eventId, field, delta);
    }

    /** A rejected event must leave current, processed, dirty and hot untouched. */
    private void assertNoPartialWrite(String expectedPlay, Double expectedHot, boolean expectedDirty) {
        assertEquals(expectedPlay, hash("playTimes"), "current hash must stay unchanged");
        assertNull(redis.opsForValue().get(RedisKeyUtils.processedKey(eventId)),
                "processed key must not be written");
        assertEquals(expectedDirty, isDirty(), "dirty marker must stay unchanged");
        assertEquals(expectedHot, hotScore(), "hot score must stay unchanged");
    }

    @Test
    void initScriptPreservesAboveDoublePrecision() {
        redis.opsForValue().set(gateKey(), OWNER_TOKEN);
        String result = redis.execute(INIT,
                List.of(currentKey(), RedisKeyUtils.dirtyVideo(),
                        RedisKeyUtils.feedHotVideos(), gateKey()),
                String.valueOf(redisVid), INIT_HOT_SCORE,
                String.valueOf(TWO_POW_53_PLUS_ONE), "0", "0", "0", "0", "0", "0", "0",
                "false", OWNER_TOKEN);
        assertEquals("INITIALIZED", result);
        assertEquals(String.valueOf(TWO_POW_53_PLUS_ONE), hash("playTimes"));
    }

    @Test
    void initScriptRejectsOutOfRangeAndNegativeWithoutWriting() {
        redis.opsForValue().set(gateKey(), OWNER_TOKEN);
        List<String> keys = List.of(currentKey(), RedisKeyUtils.dirtyVideo(),
                RedisKeyUtils.feedHotVideos(), gateKey());
        assertEquals("COUNTER_OUT_OF_RANGE", redis.execute(INIT, keys,
                String.valueOf(redisVid), INIT_HOT_SCORE,
                "9223372036854775808", "0", "0", "0", "0", "0", "0", "0",
                "false", OWNER_TOKEN));
        assertEquals("INVALID_ARGUMENT", redis.execute(INIT, keys,
                String.valueOf(redisVid), INIT_HOT_SCORE,
                "-1", "0", "0", "0", "0", "0", "0", "0",
                "false", OWNER_TOKEN));
        assertEquals(Boolean.FALSE, redis.hasKey(currentKey()));
    }

    @Test
    void redisIncrementIsExactAboveDoublePrecision() {
        initReady();
        String first = eventId;
        assertEquals("APPLIED", increment(first, "playTimes", String.valueOf(TWO_POW_53_PLUS_ONE)));
        assertEquals(String.valueOf(TWO_POW_53_PLUS_ONE), hash("playTimes"));
        Long firstTtl = processedTtl(first);
        assertNotNull(firstTtl);
        assertTrue(firstTtl > 0 && firstTtl <= 3600L, "ttl=" + firstTtl);

        // New event, same field: must be applied again, not treated as a replay.
        String second = nextEvent();
        assertEquals("APPLIED", increment(second, "playTimes", "1"));
        assertEquals("9007199254740994", hash("playTimes"));
        assertTrue(Double.isFinite(hotScore()));
    }

    @Test
    void duplicateEventIsIgnoredButNewEventIsApplied() {
        initReady();
        String first = eventId;
        assertEquals("APPLIED", increment(first, "playTimes", "5"));
        assertEquals("5", hash("playTimes"));
        assertEquals(Double.valueOf(11.5), hotScore());

        // Same event id + same delta: DUPLICATE, no second write anywhere.
        assertEquals("DUPLICATE", increment(first, "playTimes", "5"));
        assertEquals("5", hash("playTimes"));
        assertEquals(Double.valueOf(11.5), hotScore());
        Long ttl = processedTtl(first);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 3600L, "ttl=" + ttl);

        // New event id + same delta: applied once more.
        String second = nextEvent();
        assertEquals("APPLIED", increment(second, "playTimes", "5"));
        assertEquals("10", hash("playTimes"));
        assertEquals(Double.valueOf(12.5), hotScore());
        assertNotNull(processedTtl(second));
    }

    @Test
    void redisNegativeAndOverflowAreGuardedBeforeAnyWrite() {
        initReady();
        assertEquals("NEGATIVE_RESULT", increment("playTimes", "-1"));
        assertNoPartialWrite("0", Double.valueOf(INIT_HOT_SCORE), false);

        // 上界只由真实计数状态决定：把 Hash 的当前值设为 Long.MAX，而不是删 key 再造。
        redis.opsForHash().put(currentKey(), "playTimes", String.valueOf(Long.MAX_VALUE));
        nextEvent();
        assertEquals("COUNTER_OUT_OF_RANGE", increment("playTimes", "1"));
        assertNoPartialWrite(String.valueOf(Long.MAX_VALUE), Double.valueOf(INIT_HOT_SCORE), false);

        // |delta| 自身超过 Long.MAX 同样必须拒绝，hash 保持原值。
        nextEvent();
        assertEquals("COUNTER_OUT_OF_RANGE", increment("playTimes", "9223372036854775808"));
        assertNoPartialWrite(String.valueOf(Long.MAX_VALUE), Double.valueOf(INIT_HOT_SCORE), false);
    }

    @Test
    void redisBadOrMissingFieldIsExplicitAndNotTreatedAsZero() {
        initReady();
        redis.opsForHash().put(currentKey(), "playTimes", "not-a-number");
        assertEquals("NEEDS_REBUILD", increment("playTimes", "1"));
        assertEquals("not-a-number", hash("playTimes"));
        assertNull(redis.opsForValue().get(RedisKeyUtils.processedKey(eventId)));
        assertFalse(isDirty());

        nextEvent();
        redis.opsForHash().delete(currentKey(), "playTimes");
        assertEquals("NEEDS_REBUILD", increment("playTimes", "1"));
        assertNull(hash("playTimes"));
        assertNull(redis.opsForValue().get(RedisKeyUtils.processedKey(eventId)));
        assertFalse(isDirty());
    }

    @Test
    void redisMaxBoundaryAppliesOnceThenRejects() {
        initReady(zerosWithPlay(String.valueOf(Long.MAX_VALUE - 1)));
        assertEquals("APPLIED", increment("playTimes", "1"));
        assertEquals(String.valueOf(Long.MAX_VALUE), hash("playTimes"));

        Double hotAfterApply = hotScore();
        assertTrue(isDirty());
        nextEvent();
        assertEquals("COUNTER_OUT_OF_RANGE", increment("playTimes", "1"));
        assertEquals(String.valueOf(Long.MAX_VALUE), hash("playTimes"));
        assertNoPartialWrite(String.valueOf(Long.MAX_VALUE), hotAfterApply, true);
    }
}
