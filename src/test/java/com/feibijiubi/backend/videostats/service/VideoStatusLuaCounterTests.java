package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.infrastructure.redis.RedisKeyUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T02 窄任务回归：Lua 原子写之前的参数校验。
 *
 * <p>覆盖：Long.MAX 边界、2^53+1 精确性、溢出/负结果不产生任何部分写入
 * （current / processed / dirty / hot 均不变）、非整数或越界 TTL、坏 current、
 * 非 canonical delta、非有限 hot score（NaN / ±inf / 浮点和溢出）、init 只写
 * canonical 字符串。复用 {@link VideoStatusCounterIsolatedTests} 的隔离配置与
 * {@code IsolationGuard}：只连 Redis 16379 / MySQL 13307，不加载 application.yml，
 * 不启用业务 RabbitMQ，只清理本测试自己生成的 vid 成员，不清空共享 dirty 集合或队列。</p>
 *
 * <p>运行：{@code ./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusLuaCounterTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusCounterIsolatedTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusCounterIsolatedTests.IsolationGuard.class)
class VideoStatusLuaCounterTests {

    private static final RedisScript<String> INIT = RedisScript.of(
            new ClassPathResource("lua/video-status-init.lua"), String.class);
    private static final RedisScript<String> INCREMENT = RedisScript.of(
            new ClassPathResource("lua/video-status-increment.lua"), String.class);

    private static final String LONG_MAX = "9223372036854775807";
    /** 2^53 + 1：double 无法精确表示，字符串路径必须原样保留。 */
    private static final String TWO_POW_53_PLUS_ONE = "9007199254740993";
    private static final String INIT_HOT_SCORE = "10.5";
    private static final String TOKEN = "t02-lua-token";
    private static final String DEFAULT_TTL = "3600";

    @Autowired
    StringRedisTemplate redis;

    int redisVid;
    String eventId;

    @BeforeEach
    void setUp() {
        // 独立 vid：不碰共享 dirty 集合里的其他成员，也不依赖数据库自增行。
        redisVid = ThreadLocalRandom.current().nextInt(900_000_000, 999_999_999);
        eventId = "t02-lua-" + UUID.randomUUID();
        cleanup();
    }

    @AfterEach
    void cleanup() {
        redis.delete(List.of(RedisKeyUtils.videoStatus(redisVid),
                RedisKeyUtils.videoStatusRebuildGate(redisVid),
                RedisKeyUtils.processedKey(eventId)));
        redis.opsForSet().remove(RedisKeyUtils.dirtyVideo(), member());
        redis.opsForZSet().remove(RedisKeyUtils.feedHotVideos(), member());
    }

    // ---------------------------------------------------------------- helpers

    private String member() {
        return String.valueOf(redisVid);
    }

    private String gateKey() {
        return RedisKeyUtils.videoStatusRebuildGate(redisVid);
    }

    private String currentKey() {
        return RedisKeyUtils.videoStatus(redisVid);
    }

    private String hotKey() {
        return RedisKeyUtils.feedHotVideos();
    }

    private String hash(String field) {
        return (String) redis.opsForHash().get(currentKey(), field);
    }

    private void setHash(String field, String value) {
        redis.opsForHash().put(currentKey(), field, value);
    }

    private boolean isDirty() {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(RedisKeyUtils.dirtyVideo(), member()));
    }

    private Double hotScore() {
        return redis.opsForZSet().score(hotKey(), member());
    }

    private void nextEvent() {
        redis.delete(RedisKeyUtils.processedKey(eventId));
        eventId = "t02-lua-" + UUID.randomUUID();
    }

    private String executeInit(String[] counters, String hotScore, String hasPending) {
        redis.opsForValue().set(gateKey(), TOKEN);
        List<Object> args = new ArrayList<>(12);
        args.add(String.valueOf(redisVid));
        args.add(hotScore);
        args.addAll(List.of(counters));
        args.add(hasPending);
        args.add(TOKEN);
        return redis.execute(INIT,
                List.of(currentKey(), RedisKeyUtils.dirtyVideo(), hotKey(), gateKey()),
                args.toArray());
    }

    private String initZeros(String playTimes) {
        return initZeros(playTimes, INIT_HOT_SCORE);
    }

    /** 初始化成功即释放门闩（真实链路由 release-gate 脚本完成）。 */
    private String initZeros(String playTimes, String hotScore) {
        String result = executeInit(
                new String[]{playTimes, "0", "0", "0", "0", "0", "0", "0"}, hotScore, "false");
        if ("INITIALIZED".equals(result)) {
            redis.delete(gateKey());
        }
        return result;
    }

    private String increment(String field, String delta, String hotDelta, String ttl) {
        return redis.execute(INCREMENT,
                List.of(currentKey(), RedisKeyUtils.dirtyVideo(),
                        RedisKeyUtils.processedKey(eventId), hotKey(), gateKey()),
                field, delta, String.valueOf(redisVid), hotDelta, ttl);
    }

    private String increment(String delta) {
        return increment("playTimes", delta, "1.0", DEFAULT_TTL);
    }

    /** 校验失败必须四件事都没发生：current 不变、processed 未写、dirty 未加、hot 不变。 */
    private void assertNoPartialWrite(String expectedPlay, Double expectedHot) {
        assertEquals(expectedPlay, hash("playTimes"), "current must stay unchanged");
        assertNull(redis.opsForValue().get(RedisKeyUtils.processedKey(eventId)),
                "processed key must not be written");
        assertFalse(isDirty(), "dirty marker must not be added");
        assertEquals(expectedHot, hotScore(), "hot score must stay unchanged");
    }

    // ------------------------------------------------------------ counter range

    @Test
    void longMaxIsAppliedExactlyThenOverflowIsRejectedWithoutPartialWrite() {
        assertEquals("INITIALIZED", initZeros(String.valueOf(Long.MAX_VALUE - 1)));

        assertEquals("APPLIED", increment("1"));
        assertEquals(LONG_MAX, hash("playTimes"));
        assertEquals(LONG_MAX, redis.opsForHash().get(currentKey(), "playTimes"));

        // 撤回上一次成功写入的旁路副作用，便于断言"拒绝时四件事都不变"。
        redis.opsForSet().remove(RedisKeyUtils.dirtyVideo(), member());
        redis.opsForZSet().add(hotKey(), member(), Double.parseDouble(INIT_HOT_SCORE));
        nextEvent();
        assertEquals("COUNTER_OUT_OF_RANGE", increment("1"));
        assertNoPartialWrite(LONG_MAX, Double.valueOf(INIT_HOT_SCORE));
    }

    @Test
    void aboveDoublePrecisionStaysExact() {
        assertEquals("INITIALIZED", initZeros("0"));

        assertEquals("APPLIED", increment(TWO_POW_53_PLUS_ONE));
        assertEquals(TWO_POW_53_PLUS_ONE, hash("playTimes"));

        nextEvent();
        assertEquals("APPLIED", increment("1"));
        assertEquals("9007199254740994", hash("playTimes"));
        // hot score 是浮点排序权重：允许近似，但不能是 NaN/inf。
        assertTrue(Double.isFinite(hotScore()));
    }

    @Test
    void overflowAndNegativeResultLeaveNoPartialWrite() {
        assertEquals("INITIALIZED", initZeros("0"));

        assertEquals("NEGATIVE_RESULT", increment("-1"));
        assertNoPartialWrite("0", Double.valueOf(INIT_HOT_SCORE));

        setHash("playTimes", LONG_MAX);
        assertEquals("COUNTER_OUT_OF_RANGE", increment("1"));
        assertNoPartialWrite(LONG_MAX, Double.valueOf(INIT_HOT_SCORE));

        // |delta| 本身超出 Long.MAX：正向和负向都必须拒绝。
        setHash("playTimes", "0");
        assertEquals("COUNTER_OUT_OF_RANGE", increment("9223372036854775808"));
        assertNoPartialWrite("0", Double.valueOf(INIT_HOT_SCORE));

        assertEquals("COUNTER_OUT_OF_RANGE", increment("-9223372036854775808"));
        assertNoPartialWrite("0", Double.valueOf(INIT_HOT_SCORE));

        // 恰好减到 0 是合法边界。
        setHash("playTimes", "5");
        assertEquals("APPLIED", increment("-5"));
        assertEquals("0", hash("playTimes"));
        assertTrue(isDirty());
        Long appliedTtl = redis.getExpire(RedisKeyUtils.processedKey(eventId));
        assertTrue(appliedTtl != null && appliedTtl > 0);
    }

    @Test
    void nonCanonicalDeltaIsRejectedWithoutPartialWrite() {
        assertEquals("INITIALIZED", initZeros("0"));
        for (String delta : List.of("+1", "01", "-0", "00", "1.0", "1e3", "-01", "", " 1")) {
            assertEquals("INVALID_ARGUMENT", increment(delta), "delta=" + delta);
            assertNoPartialWrite("0", Double.valueOf(INIT_HOT_SCORE));
        }
        // canonical 形式仍然可用。
        assertEquals("APPLIED", increment("0"));
        assertEquals("0", hash("playTimes"));
    }

    // ------------------------------------------------------------------ TTL

    @Test
    void nonIntegerOrOutOfRangeTtlIsRejectedWithoutPartialWrite() {
        assertEquals("INITIALIZED", initZeros("0"));
        for (String ttl : List.of("3600.5", "1e3", "0", "-1", "315360001", "3600 ", " 3600", "+3600")) {
            assertEquals("INVALID_ARGUMENT", increment("playTimes", "1", "1.0", ttl), "ttl=" + ttl);
            assertNoPartialWrite("0", Double.valueOf(INIT_HOT_SCORE));
        }

        // 3650 天上界本身合法，且 EX 使用整数秒。
        assertEquals("APPLIED", increment("playTimes", "1", "1.0", "315360000"));
        assertEquals("1", hash("playTimes"));
        Long ttl = redis.getExpire(RedisKeyUtils.processedKey(eventId));
        assertTrue(ttl != null && ttl > 0 && ttl <= 315_360_000L, "ttl=" + ttl);
    }

    // -------------------------------------------------------------- bad current

    @Test
    void badCurrentRequiresRebuildWithoutPartialWrite() {
        assertEquals("INITIALIZED", initZeros("0"));

        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("007", "NEEDS_REBUILD");
        cases.put("+1", "NEEDS_REBUILD");
        cases.put("-1", "NEEDS_REBUILD");
        cases.put("1.0", "NEEDS_REBUILD");
        cases.put("not-a-number", "NEEDS_REBUILD");
        cases.put("", "NEEDS_REBUILD");
        cases.put("9223372036854775808", "COUNTER_OUT_OF_RANGE");

        cases.forEach((stored, expected) -> {
            setHash("playTimes", stored);
            assertEquals(expected, increment("1"), "stored=" + stored);
            assertNoPartialWrite(stored, Double.valueOf(INIT_HOT_SCORE));
        });
    }

    // ------------------------------------------------------------- hot score

    @Test
    void nonFiniteHotScoreDeltaIsRejectedWithoutPartialWrite() {
        assertEquals("INITIALIZED", initZeros("0"));
        for (String hotDelta : List.of("NaN", "nan", "inf", "-inf", "Infinity", "1e400", "-1e400")) {
            assertEquals("INVALID_ARGUMENT", increment("playTimes", "1", hotDelta, DEFAULT_TTL),
                    "hotDelta=" + hotDelta);
            assertNoPartialWrite("0", Double.valueOf(INIT_HOT_SCORE));
        }
    }

    @Test
    void nonFiniteStoredHotScoreIsRejectedWithoutPartialWrite() {
        assertEquals("INITIALIZED", initZeros("0"));

        redis.opsForZSet().add(hotKey(), member(), Double.POSITIVE_INFINITY);
        assertEquals("HOT_SCORE_OUT_OF_RANGE", increment("playTimes", "1", "1.0", DEFAULT_TTL));
        assertNoPartialWrite("0", Double.POSITIVE_INFINITY);

        // 有限 delta 但和溢出成 inf：同样必须在 HINCRBY 之前拦住。
        redis.opsForZSet().add(hotKey(), member(), 1e308);
        assertEquals("HOT_SCORE_OUT_OF_RANGE", increment("playTimes", "1", "1e308", DEFAULT_TTL));
        assertNoPartialWrite("0", 1e308);
    }

    @Test
    void hotScoreMayBeApproximateButStaysFinite() {
        assertEquals("INITIALIZED", initZeros("0", "0.1"));

        assertEquals("APPLIED", increment("playTimes", "1", "0.2", DEFAULT_TTL));
        nextEvent();
        assertEquals("APPLIED", increment("playTimes", "1", "0.2", DEFAULT_TTL));

        Double score = hotScore();
        assertTrue(Double.isFinite(score));
        assertEquals(0.5, score, 1e-9);
    }

    // ------------------------------------------------------------------ init

    @Test
    void initRejectsNonCanonicalCountersWithoutWriting() {
        for (String bad : List.of("007", "+1", "-1", "1e3", "1.5", "", " 1")) {
            String[] counters = {bad, "0", "0", "0", "0", "0", "0", "0"};
            assertEquals("INVALID_ARGUMENT", executeInit(counters, INIT_HOT_SCORE, "false"),
                    "counter=" + bad);
            assertInitWroteNothing();
        }

        String[] outOfRange = {"9223372036854775808", "0", "0", "0", "0", "0", "0", "0"};
        assertEquals("COUNTER_OUT_OF_RANGE", executeInit(outOfRange, INIT_HOT_SCORE, "false"));
        assertInitWroteNothing();
    }

    @Test
    void initRejectsNonFiniteHotScoreWithoutWriting() {
        String[] counters = {"0", "0", "0", "0", "0", "0", "0", "0"};
        for (String hot : List.of("NaN", "inf", "-inf", "1e400")) {
            assertEquals("INVALID_ARGUMENT", executeInit(counters, hot, "false"), "hot=" + hot);
            assertInitWroteNothing();
        }
    }

    @Test
    void initWritesCanonicalCountersExactly() {
        String[] counters = {LONG_MAX, TWO_POW_53_PLUS_ONE, "0", "0", "0", "0", "0", "1"};
        assertEquals("INITIALIZED", executeInit(counters, INIT_HOT_SCORE, "true"));

        assertEquals(String.valueOf(redisVid), hash("vid"));
        assertEquals(LONG_MAX, hash("playTimes"));
        assertEquals(TWO_POW_53_PLUS_ONE, hash("likeTimes"));
        assertEquals("1", hash("danmuTimes"));
        assertTrue(isDirty(), "hasPending=true must add the dirty marker");
        assertEquals(Double.valueOf(INIT_HOT_SCORE), hotScore());

        // 初始化后立刻用同一份状态做边界加法：门闩已释放，MAX 再加 1 必须拒绝。
        redis.delete(gateKey());
        assertEquals("COUNTER_OUT_OF_RANGE", increment("1"));
        assertEquals(LONG_MAX, hash("playTimes"));
        assertNull(redis.opsForValue().get(RedisKeyUtils.processedKey(eventId)));
    }

    private void assertInitWroteNothing() {
        assertEquals(Boolean.FALSE, redis.hasKey(currentKey()), "current hash must not be created");
        assertFalse(isDirty(), "dirty marker must not be added");
        assertNull(hotScore(), "hot member must not be added");
    }

    // ----------------------------------------------------- gate / idempotency

    @Test
    void rebuildGateBlocksIncrementWithoutWriting() {
        assertEquals("INITIALIZED", initZeros("0"));
        redis.opsForValue().set(gateKey(), "another-owner");

        assertEquals("NEEDS_REBUILD", increment("1"));
        assertNoPartialWrite("0", Double.valueOf(INIT_HOT_SCORE));
    }

    @Test
    void duplicateEventIsNotAppliedTwice() {
        assertEquals("INITIALIZED", initZeros("0"));

        assertEquals("APPLIED", increment("1"));
        assertEquals("1", hash("playTimes"));
        assertTrue(isDirty());
        assertEquals(Double.valueOf(11.5), hotScore());

        assertEquals("DUPLICATE", increment("1"));
        assertEquals("1", hash("playTimes"));
        assertEquals(Double.valueOf(11.5), hotScore());
        Long ttl = redis.getExpire(RedisKeyUtils.processedKey(eventId));
        assertTrue(ttl != null && ttl > 0 && ttl <= 3600L, "ttl=" + ttl);
    }
}
