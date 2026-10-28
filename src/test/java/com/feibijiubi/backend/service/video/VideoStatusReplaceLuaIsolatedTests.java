package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.config.RedisScriptConfig;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.protocol.CommandType;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T12-C1 {@code video-status-replace.lua} 的真实隔离 Redis 回归：只允许
 * {@code compose.test.yaml} 的 Redis 16379 / MySQL 13307，不加载 application.yml。
 *
 * <p>只在显式 JVM 参数 {@code -DvideoStatusIT=true} 时运行，复用
 * {@link VideoStatusCounterIsolatedTests.IsolationGuard}（它同时锁死 datasource 与
 * Redis host/port，并拒绝其它 {@code spring.data.redis.*} 覆盖）。脚本 bean 由
 * {@link RedisScriptConfig#videoStatusReplaceScript()} 提供，本类<b>不</b>直接读
 * classpath 文件，因此 bean 装配本身也在覆盖范围内。</p>
 *
 * <h2>覆盖的不变量</h2>
 * <ol>
 *   <li>正常替换：一次 HSET 写九个字段（vid + 八计数），hot 同步 ZADD，门闩保持原 token，
 *       processed 证据不变；</li>
 *   <li>pending=false 不 SREM dirty（保守保留），其它 vid 的 dirty 成员不受影响；</li>
 *   <li>拒绝矩阵：冷 miss、current 非 Hash、current 带 TTL、未知/缺失字段、损坏取值、
 *       vid 不一致、旧 owner token、缺失门闩、dirty/hot 类型错误、非法参数（vid/hot/计数/
 *       pending 标志）、计数超过 Long.MAX；每种拒绝都断言“前后状态完全相同”；</li>
 *   <li>数值边界：Long.MAX 与 2^53+1 原样保留，不经 double；</li>
 *   <li>运行时部分失败：用 ACL 只对专用测试用户名禁用某一条写命令，验证阶段码
 *       （HSET / DIRTY / HOT）与“已写入的部分”，从而证明脚本不承诺整体回滚；专用 ACL
 *       用户在 finally 中删除，不改动 default 用户或全局 Redis 配置。</li>
 * </ol>
 *
 * <p>隔离约定：每个用例用自己的随机 vid（9 亿段，不碰数据库自增行），只删除自己的
 * current/gate 键与自己的 dirty/hot 成员；错误类型用例使用<b>专用键名</b>，绝不把共享的
 * dirty 集合或 hot zset 改成别的类型，避免污染其它隔离用例。</p>
 *
 * <p>运行：{@code ./mvnw -o -DvideoStatusIT=true -Dtest=VideoStatusReplaceLuaIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusReplaceLuaIsolatedTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusCounterIsolatedTests.IsolationGuard.class)
class VideoStatusReplaceLuaIsolatedTests {

    /** 本类只用 Redis；RabbitMQ / Mongo / Redisson 一律不装配。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
    @Import(RedisScriptConfig.class)
    static class TestApplication {
    }

    private static final String LONG_MAX = "9223372036854775807";
    /** 只允许 compose.test.yaml 的隔离 Redis。 */
    private static final String ISOLATED_REDIS_HOST = "127.0.0.1";
    private static final int ISOLATED_REDIS_PORT = 16379;
    /** 2^53 + 1：double 无法精确表示，替换必须原样写入字符串。 */
    private static final String TWO_POW_53_PLUS_ONE = "9007199254740993";
    private static final String OWNER_TOKEN = "t12c1-owner-token";
    private static final String[] COUNTER_FIELDS = {
            "playTimes", "likeTimes", "unlikeTimes", "commentTimes",
            "coinTimes", "shareTimes", "collectTimes", "danmuTimes"};
    private static final String[] ZEROS = {"0", "0", "0", "0", "0", "0", "0", "0"};
    private static final String REPLACED = "REPLACED";
    private static final String INVALID_REDIS_TYPE = "INVALID_REDIS_TYPE";
    private static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";

    @Resource(name = "videoStatusReplaceScript")
    private DefaultRedisScript<String> replaceScript;

    @Autowired
    StringRedisTemplate redis;

    int vid;
    int otherVid;
    String aclUser;
    RedisClient aclClient;

    @BeforeEach
    void setUp() {
        vid = ThreadLocalRandom.current().nextInt(900_000_000, 950_000_000);
        otherVid = vid + 1;
        aclUser = null;
        aclClient = RedisClient.create(RedisURI.create(
                "redis://" + ISOLATED_REDIS_HOST + ":" + ISOLATED_REDIS_PORT));
        purgeOwnKeys();
    }

    @AfterEach
    void cleanup() {
        try {
            if (aclUser != null) {
                deleteAclUser(aclUser);
                aclUser = null;
            }
        } finally {
            purgeOwnKeys();
            if (aclClient != null) {
                aclClient.shutdown();
                aclClient = null;
            }
        }
    }

    /** 只删本用例自己的 vid 键与自己的 dirty/hot 成员，不清共享集合。 */
    private void purgeOwnKeys() {
        redis.delete(List.of(currentKey(), gateKey()));
        redis.opsForSet().remove(RedisKeyUtils.dirtyVideo(), member());
        redis.opsForZSet().remove(RedisKeyUtils.feedHotVideos(), member());
        redis.opsForSet().remove(RedisKeyUtils.dirtyVideo(), String.valueOf(otherVid));
        redis.opsForZSet().remove(RedisKeyUtils.feedHotVideos(), String.valueOf(otherVid));
    }

    // ------------------------------------------------------------------ helpers

    private String member() {
        return String.valueOf(vid);
    }

    private String currentKey() {
        return RedisKeyUtils.videoStatus(vid);
    }

    private String gateKey() {
        return RedisKeyUtils.videoStatusRebuildGate(vid);
    }

    private String dirtyKey() {
        return RedisKeyUtils.dirtyVideo();
    }

    private String hotKey() {
        return RedisKeyUtils.feedHotVideos();
    }

    private void holdGate() {
        redis.opsForValue().set(gateKey(), OWNER_TOKEN);
    }

    /** 直接铺一个完整的九字段 Hash，不依赖 init 脚本，避免与被测脚本互相背书。 */
    private void seedCurrent(String... counters) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("vid", member());
        for (int i = 0; i < COUNTER_FIELDS.length; i++) {
            fields.put(COUNTER_FIELDS[i], counters[i]);
        }
        redis.opsForHash().putAll(currentKey(), fields);
    }

    private List<String> keys() {
        return keys(dirtyKey(), hotKey());
    }

    private List<String> keys(String dirtyKey, String hotKey) {
        return List.of(currentKey(), dirtyKey, hotKey, gateKey());
    }

    private Object[] args(
            String[] counters,
            String hotScore,
            String hasPending,
            String token
    ) {
        List<Object> args = new ArrayList<>(12);
        args.add(member());
        args.add(hotScore);
        args.addAll(Arrays.asList(counters));
        args.add(hasPending);
        args.add(token);
        return args.toArray();
    }

    private String replace(String[] counters, String hotScore, String hasPending) {
        return replace(counters, hotScore, hasPending, OWNER_TOKEN);
    }

    private String replace(
            String[] counters,
            String hotScore,
            String hasPending,
            String token
    ) {
        return replace(counters, hotScore, hasPending, token, dirtyKey(), hotKey());
    }

    private String replace(
            String[] counters,
            String hotScore,
            String hasPending,
            String token,
            String dirtyKey,
            String hotKey
    ) {
        return redis.execute(
                replaceScript,
                keys(dirtyKey, hotKey),
                args(counters, hotScore, hasPending, token)
        );
    }

    /** 一次观测：Hash 全部字段、TTL、dirty 成员、hot score、门闩 token。 */
    private Map<String, Object> observe() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("hash", new LinkedHashMap<>(redis.opsForHash().entries(currentKey())));
        state.put("ttl", redis.getExpire(currentKey()));
        state.put("dirty", Boolean.TRUE.equals(
                redis.opsForSet().isMember(dirtyKey(), member())));
        state.put("hot", redis.opsForZSet().score(hotKey(), member()));
        state.put("gate", redis.opsForValue().get(gateKey()));
        return state;
    }

    private String hashField(String field) {
        return (String) redis.opsForHash().get(currentKey(), field);
    }

    private void assertHashEquals(String[] expected) {
        for (int i = 0; i < COUNTER_FIELDS.length; i++) {
            assertEquals(expected[i], hashField(COUNTER_FIELDS[i]),
                    "字段 " + COUNTER_FIELDS[i] + " 必须等于替换值");
        }
    }

    // ------------------------------------------------------- 正常替换与 dirty 语义

    @Test
    void replaceRewritesAllNineFieldsHotAndKeepsGate() {
        seedCurrent("1", "2", "3", "4", "5", "6", "7", "8");
        holdGate();
        String[] next = {"11", "12", "13", "14", "15", "16", "17", "18"};

        assertEquals(REPLACED, replace(next, "99.5", "true"));

        assertEquals(member(), hashField("vid"), "vid 字段必须保持同一身份");
        assertHashEquals(next);
        assertTrue(Boolean.TRUE.equals(
                redis.opsForSet().isMember(dirtyKey(), member())), "pending=true 必须 SADD dirty");
        assertEquals(99.5D, redis.opsForZSet().score(hotKey(), member()),
                "hot 必须被 ZADD 到新分数");
        assertEquals(OWNER_TOKEN, redis.opsForValue().get(gateKey()),
                "替换不得释放门闩");
        assertEquals(-1L, redis.getExpire(currentKey()),
                "替换不得给 current 加 TTL");
    }

    @Test
    void replaceWithoutPendingKeepsDirtyMemberAndOtherVideosDirty() {
        seedCurrent(ZEROS);
        holdGate();
        redis.opsForSet().add(dirtyKey(), member(), String.valueOf(otherVid));

        assertEquals(REPLACED, replace(ZEROS, "1", "false"));

        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(dirtyKey(), member())),
                "pending=false 不 SREM：保守保留 dirty，宁可多刷一次");
        assertTrue(Boolean.TRUE.equals(
                        redis.opsForSet().isMember(dirtyKey(), String.valueOf(otherVid))),
                "不得影响其它 vid 的 dirty 成员");
        assertEquals(1D, redis.opsForZSet().score(hotKey(), member()));
    }

    @Test
    void processedEvidenceIsNotTouchedByReplace() {
        seedCurrent(ZEROS);
        holdGate();
        String processedKey = RedisKeyUtils.processedKey("t12c1-" + UUID.randomUUID());
        redis.opsForValue().set(processedKey, "1");
        redis.expire(processedKey, java.time.Duration.ofMinutes(30));
        Long ttlBefore = redis.getExpire(processedKey);
        try {
            assertEquals(REPLACED, replace(ZEROS, "1", "true"));

            assertEquals("1", redis.opsForValue().get(processedKey),
                    "替换不得改写 processed 幂等证据");
            assertTrue(ttlBefore > 0 && redis.getExpire(processedKey) > 0,
                    "替换不得刷新/移除 processed TTL");
        } finally {
            redis.delete(processedKey);
        }
    }

    // ------------------------------------------------------------ 冷 miss 与类型

    @Test
    void coldMissIsRefusedWithCurrentMissing() {
        holdGate();
        Map<String, Object> before = observe();

        assertEquals("CURRENT_MISSING", replace(ZEROS, "1", "true"));

        assertEquals(before, observe());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(currentKey())),
                "replace 绝不能当作初始化：current 仍必须不存在");
    }

    @Test
    void nonHashCurrentIsRefusedWithInvalidRedisType() {
        holdGate();
        redis.opsForValue().set(currentKey(), "not-a-hash");
        // 类型错误时不能用 observe()（它会按 Hash 读），单独记录字符串值、TTL 与其它键。
        Long ttlBefore = redis.getExpire(currentKey());
        assertFalse(Boolean.TRUE.equals(
                redis.opsForSet().isMember(dirtyKey(), member())));
        assertNull(redis.opsForZSet().score(hotKey(), member()));

        assertEquals(INVALID_REDIS_TYPE, replace(ZEROS, "1", "true"));

        assertEquals("not-a-hash", redis.opsForValue().get(currentKey()),
                "拒绝时不得改写既有字符串键");
        assertEquals(ttlBefore, redis.getExpire(currentKey()));
        assertFalse(Boolean.TRUE.equals(
                redis.opsForSet().isMember(dirtyKey(), member())), "拒绝时不得写 dirty");
        assertNull(redis.opsForZSet().score(hotKey(), member()), "拒绝时不得写 hot");
        assertEquals(OWNER_TOKEN, redis.opsForValue().get(gateKey()));
    }

    @Test
    void currentWithTtlIsRefusedWithCurrentHasTtl() {
        seedCurrent("1", "0", "0", "0", "0", "0", "0", "0");
        holdGate();
        redis.expire(currentKey(), java.time.Duration.ofMinutes(10));
        Map<String, Object> before = observe();

        assertEquals("CURRENT_HAS_TTL", replace(ZEROS, "1", "true"));

        assertEquals(before, observe());
        assertTrue(redis.getExpire(currentKey()) > 0, "拒绝后 TTL 必须原样保留");
    }

    @Test
    void unknownHashFieldIsRefusedWithUnexpectedFields() {
        seedCurrent(ZEROS);
        holdGate();
        redis.opsForHash().put(currentKey(), "legacyField", "1");
        Map<String, Object> before = observe();

        assertEquals("UNEXPECTED_FIELDS", replace(ZEROS, "1", "true"));

        assertEquals(before, observe());
        assertNull(redis.opsForZSet().score(hotKey(), member()),
                "拒绝时不得写 hot");
    }

    @Test
    void incompleteHashIsRefusedWithUnexpectedFields() {
        seedCurrent(ZEROS);
        holdGate();
        redis.opsForHash().delete(currentKey(), "danmuTimes");
        Map<String, Object> before = observe();

        assertEquals("UNEXPECTED_FIELDS", replace(ZEROS, "1", "true"));

        assertEquals(before, observe());
    }

    @Test
    void corruptStoredCounterIsRefusedWithCurrentInvalidValue() {
        seedCurrent("1", "0", "0", "0", "0", "0", "0", "0");
        holdGate();
        redis.opsForHash().put(currentKey(), "playTimes", "007");
        Map<String, Object> before = observe();

        assertEquals("CURRENT_INVALID_VALUE", replace(ZEROS, "1", "true"));

        assertEquals(before, observe());
    }

    @Test
    void wrongDirtyTypeIsRefusedWithInvalidRedisType() {
        seedCurrent(ZEROS);
        holdGate();
        // 专用键名：绝不把共享 dirty 集合改成别的类型。
        String dedicatedDirty = "t12c1:wrong-dirty:" + UUID.randomUUID();
        redis.opsForValue().set(dedicatedDirty, "not-a-set");
        Map<String, Object> before = observe();
        try {
            assertEquals(INVALID_REDIS_TYPE,
                    replace(ZEROS, "1", "true", OWNER_TOKEN, dedicatedDirty, hotKey()));

            assertEquals(before, observe());
        } finally {
            redis.delete(dedicatedDirty);
        }
    }

    @Test
    void wrongHotTypeIsRefusedWithInvalidRedisType() {
        seedCurrent(ZEROS);
        holdGate();
        String dedicatedHot = "t12c1:wrong-hot:" + UUID.randomUUID();
        redis.opsForValue().set(dedicatedHot, "not-a-zset");
        Map<String, Object> before = observe();
        try {
            assertEquals(INVALID_REDIS_TYPE,
                    replace(ZEROS, "1", "true", OWNER_TOKEN, dirtyKey(), dedicatedHot));

            assertEquals(before, observe());
        } finally {
            redis.delete(dedicatedHot);
        }
    }

    // ------------------------------------------------------------ 门闩与 vid 身份

    @Test
    void staleOwnerTokenIsRefusedAndCannotWrite() {
        seedCurrent("1", "0", "0", "0", "0", "0", "0", "0");
        // 门闩已被新 owner 接管：旧 token 不得覆盖新结果。
        redis.opsForValue().set(gateKey(), "t12c1-new-owner");
        Map<String, Object> before = observe();

        assertEquals("STALE_REBUILD", replace(ZEROS, "1", "true", "t12c1-old-owner"));

        assertEquals(before, observe());
        assertEquals("t12c1-new-owner", redis.opsForValue().get(gateKey()));
    }

    @Test
    void missingGateIsRefusedAndCannotWrite() {
        seedCurrent(ZEROS);
        Map<String, Object> before = observe();

        assertEquals("STALE_REBUILD", replace(ZEROS, "1", "true"));

        assertEquals(before, observe());
    }

    @Test
    void vidMismatchIsRefusedWithVidMismatch() {
        seedCurrent(ZEROS);
        holdGate();
        Map<String, Object> before = observe();
        List<Object> args = new ArrayList<>(Arrays.asList(
                args(ZEROS, "1", "true", OWNER_TOKEN)));
        args.set(0, String.valueOf(otherVid));

        assertEquals("VID_MISMATCH",
                redis.execute(replaceScript, keys(), args.toArray()));

        assertEquals(before, observe());
    }

    @Test
    void zeroVidIsRefusedWithInvalidArgument() {
        seedCurrent(ZEROS);
        holdGate();
        Map<String, Object> before = observe();
        List<Object> args = new ArrayList<>(Arrays.asList(
                args(ZEROS, "1", "true", OWNER_TOKEN)));
        args.set(0, "0");

        assertEquals(INVALID_ARGUMENT,
                redis.execute(replaceScript, keys(), args.toArray()));

        assertEquals(before, observe());
    }

    // ------------------------------------------------------------------ 数值边界

    @Test
    void longMaxCountersAreAcceptedExactly() {
        seedCurrent(ZEROS);
        holdGate();
        String[] maxed = {LONG_MAX, LONG_MAX, LONG_MAX, LONG_MAX,
                LONG_MAX, LONG_MAX, LONG_MAX, LONG_MAX};

        assertEquals(REPLACED, replace(maxed, "1", "true"));

        assertHashEquals(maxed);
    }

    @Test
    void twoPow53PlusOneIsPreservedExactly() {
        seedCurrent(ZEROS);
        holdGate();
        String[] precise = {TWO_POW_53_PLUS_ONE, TWO_POW_53_PLUS_ONE, TWO_POW_53_PLUS_ONE,
                TWO_POW_53_PLUS_ONE, TWO_POW_53_PLUS_ONE, TWO_POW_53_PLUS_ONE,
                TWO_POW_53_PLUS_ONE, TWO_POW_53_PLUS_ONE};

        assertEquals(REPLACED, replace(precise, "1", "true"));

        assertHashEquals(precise);
    }

    @Test
    void counterAboveLongMaxIsRefusedWithCounterOutOfRange() {
        seedCurrent(ZEROS);
        holdGate();
        String[] overflow = {"9223372036854775808", "0", "0", "0", "0", "0", "0", "0"};
        Map<String, Object> before = observe();

        assertEquals("COUNTER_OUT_OF_RANGE", replace(overflow, "1", "true"));

        assertEquals(before, observe());
    }

    @Test
    void nonCanonicalCounterIsRefusedWithInvalidArgument() {
        seedCurrent(ZEROS);
        holdGate();
        Map<String, Object> before = observe();

        for (String bad : List.of("007", "+1", "-1", "0.5", "1e3", "", " 1")) {
            String[] counters = {"1", "0", "0", "0", "0", "0", "0", "0"};
            counters[3] = bad;
            assertEquals(INVALID_ARGUMENT, replace(counters, "1", "true"),
                    "非 canonical 计数必须拒绝: " + bad);
        }

        assertEquals(before, observe());
    }

    @Test
    void nonFiniteHotScoreIsRefusedWithInvalidArgument() {
        seedCurrent(ZEROS);
        holdGate();
        Map<String, Object> before = observe();

        for (String bad : List.of("nan", "inf", "-inf", "abc", "")) {
            assertEquals(INVALID_ARGUMENT, replace(ZEROS, bad, "true"),
                    "非法 hot score 必须拒绝: " + bad);
        }

        assertEquals(before, observe());
    }

    @Test
    void invalidPendingFlagIsRefusedWithInvalidArgument() {
        seedCurrent(ZEROS);
        holdGate();
        Map<String, Object> before = observe();

        for (String bad : List.of("TRUE", "1", "", "yes")) {
            assertEquals(INVALID_ARGUMENT, replace(ZEROS, "1", bad),
                    "hasPending 只接受 true/false: " + bad);
        }

        assertEquals(before, observe());
    }

    // ------------------------------------------------- 运行时部分失败（ACL 注入）

    @Test
    void deniedHashWriteReportsHsetStageAndWritesNothing() {
        seedCurrent("1", "2", "3", "4", "5", "6", "7", "8");
        holdGate();
        String[] next = {"11", "12", "13", "14", "15", "16", "17", "18"};
        Map<String, Object> before = observe();

        assertEquals("REPLACE_HSET_FAILED",
                executeAsRestrictedUser(CommandType.HSET, next, "99.5", "true"));

        assertEquals(before, observe());
        assertNull(redis.opsForZSet().score(hotKey(), member()),
                "HSET 阶段失败时不得继续写 hot");
    }

    @Test
    void deniedDirtyWriteReportsDirtyStageAfterHashWrite() {
        seedCurrent("1", "2", "3", "4", "5", "6", "7", "8");
        holdGate();
        String[] next = {"11", "12", "13", "14", "15", "16", "17", "18"};

        assertEquals("REPLACE_DIRTY_FAILED",
                executeAsRestrictedUser(CommandType.SADD, next, "99.5", "true"));

        // 阶段码之外的真相：Hash 已经被写入了。脚本不承诺整体回滚，调用方必须按阶段诊断。
        assertHashEquals(next);
        assertFalse(Boolean.TRUE.equals(
                        redis.opsForSet().isMember(dirtyKey(), member())),
                "SADD 被拒绝，dirty 成员不应存在");
        assertNull(redis.opsForZSet().score(hotKey(), member()),
                "dirty 阶段失败后不得继续写 hot");
    }

    @Test
    void deniedHotWriteReportsHotStageAfterHashAndDirty() {
        seedCurrent("1", "2", "3", "4", "5", "6", "7", "8");
        holdGate();
        String[] next = {"11", "12", "13", "14", "15", "16", "17", "18"};

        assertEquals("REPLACE_HOT_FAILED",
                executeAsRestrictedUser(CommandType.ZADD, next, "99.5", "true"));

        assertHashEquals(next);
        assertTrue(Boolean.TRUE.equals(
                        redis.opsForSet().isMember(dirtyKey(), member())),
                "HSET 与 SADD 都已成功");
        assertNull(redis.opsForZSet().score(hotKey(), member()),
                "ZADD 被拒绝，hot 分数不应存在");
    }

    // ------------------------------------------------------------- ACL 注入工具

    /**
     * 用只对该测试用户名生效的 ACL 规则禁用一条写命令，再在专用连接上执行脚本。
     *
     * <p>Redis 7 会在脚本内逐条命令做 ACL 校验，失败以 Lua error 抛给 {@code redis.call}，
     * 因此这正是“校验已通过、运行时写入失败”的真实注入，不需要伪造命令或改全局配置。
     * 专用用户在 {@link #cleanup()} 中删除，default 用户与既有连接不受影响。</p>
     */
    private String executeAsRestrictedUser(
            CommandType deniedCommand,
            String[] counters,
            String hotScore,
            String hasPending
    ) {
        String user = "t12c1-" + UUID.randomUUID();
        String password = "t12c1-" + UUID.randomUUID();
        aclUser = user;
        // 只对这个临时用户禁用一条写命令：+@all 后再 -<command>。
        acl(commands -> commands.aclSetuser(user, new AclSetuserArgs()
                .reset()
                .on()
                .addPassword(password)
                .allKeys()
                .allChannels()
                .allCommands()
                .removeCommand(deniedCommand)));

        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(ISOLATED_REDIS_HOST, ISOLATED_REDIS_PORT);
        standalone.setUsername(user);
        standalone.setPassword(RedisPassword.of(password));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone);
        factory.afterPropertiesSet();
        try {
            StringRedisTemplate restricted = new StringRedisTemplate(factory);
            return restricted.execute(
                    replaceScript,
                    keys(),
                    args(counters, hotScore, hasPending, OWNER_TOKEN)
            );
        } finally {
            factory.destroy();
            deleteAclUser(user);
            aclUser = null;
        }
    }

    /**
     * ACL 管理走独立的 Lettuce 客户端（default 用户）：Spring Data Redis 的裸
     * {@code RedisConnection#execute} 无法解码整数回复，不适合发 ACL 命令。
     * 不修改任何全局配置，只增删本测试自己的临时用户。
     */
    private <T> T acl(Function<RedisCommands<String, String>, T> action) {
        try (StatefulRedisConnection<String, String> connection = aclClient.connect()) {
            return action.apply(connection.sync());
        }
    }

    private void deleteAclUser(String user) {
        acl(commands -> commands.aclDeluser(user));
    }
}
