package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mq.VideoStatusBatchFlushScheduler;
import com.feibijiubi.backend.mq.VideoStatusDirtyRecoveryScheduler;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusBatchFlushServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T11 真实 Redis + MySQL 收敛验收：250 个 pending、批量 100，前 100 个持续失败，
 * 后 150 个 dirty 丢失。
 *
 * <p>与 {@link VideoStatusDirtyRecoveryFairnessIsolatedTests} 的分工：那个类用真实 MySQL
 * 验证 SQL keyset 顺序，Redis SADD 用桩；本类同时使用真实 Redis 与真实 MySQL，跑真实的
 * {@link VideoStatusDirtyRecoveryScheduler} 和 {@link VideoStatusBatchFlushScheduler}，
 * 只对“本用例自己的头部 vid”注入 SADD 故障，其余依赖全部真实。</p>
 *
 * <p>断言：</p>
 * <ol>
 *   <li>四轮恢复内，150 个健康尾部视频全部获得真实 dirty 机会；失败头部也获得首次真实机会；</li>
 *   <li>健康尾部经真实落库后，DB 计数逐个等于独立 oracle（直接由事件表 SUM(delta) 计算）；</li>
 *   <li>失败头部在故障期间仍是 status 1，不转 status 3（不误隔离），也没有被偷偷落库；</li>
 *   <li>释放故障并越过本地退避后，头部重新被补回 dirty 并落库，最终 250 个视频全部收敛，
 *       总计数仍等于独立 oracle。</li>
 * </ol>
 *
 * <p>数据隔离：只新增自己的用户/视频/status 1 事件；dirty 使用本用例专属 Redis key
 * （通过测试内包装器映射），不清理共享表、不删除任何其它测试数据。显式
 * {@code -DvideoStatusIT=true} 才运行，并复用
 * {@link VideoStatusCounterIsolatedTests.IsolationGuard} 的 MySQL + Redis 连接护栏。</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusDirtyRecoveryRealConvergenceTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusCounterIsolatedTests.IsolationGuard.class)
class VideoStatusDirtyRecoveryRealConvergenceTests {

    private static final int BATCH = 100;
    private static final int HEAD = 100;
    private static final int TAIL = 150;
    private static final int TOTAL = HEAD + TAIL;
    private static final Duration BACKOFF_ADVANCE = Duration.ofSeconds(3);

    /**
     * 真实 MySQL + Redis + MyBatis + 真实落库/恢复组件；不启动 RabbitMQ，也不启用
     * {@code @EnableScheduling}。调度由测试显式调用真实 Bean 的真实方法。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
    @EnableConfigurationProperties(VideoStatusProperties.class)
    @MapperScan("com.feibijiubi.backend.mapper")
    @Import({VideoStatusBatchFlushServiceImpl.class, VideoStatusVidMutex.class, JsonUtils.class})
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
    @Autowired JsonUtils jsonUtils;
    @Autowired VideoStatusConsumedEventMapper consumedEventMapper;
    @Autowired VideoStatusBatchFlushService flushService;
    @Autowired VideoStatusVidMutex vidMutex;

    @Test
    void healthyTailConvergesInFourRoundsAndFailedHeadIsNotQuarantined() {
        Seeded seeded = seedVideosAndPendingEvents();
        List<Integer> vids = seeded.vids();
        Set<Integer> head = new TreeSet<>(vids.subList(0, HEAD));
        Set<Integer> tail = new TreeSet<>(vids.subList(HEAD, TOTAL));
        int firstVid = vids.get(0);
        int lastVid = vids.get(TOTAL - 1);

        Map<Integer, Long> oracle = independentOracle(firstVid, lastVid);
        assertEquals(TOTAL, oracle.size(), "oracle must cover exactly this test's 250 vids");

        String dirtyKey = "test:t11:real-convergence:" + UUID.randomUUID() + ":dirty";
        MutableClock clock = new MutableClock();
        ScopedDirtySetOperations sets = new ScopedDirtySetOperations(
                redis, jsonUtils, dirtyKey, head
        );
        try {
            VideoStatusProperties properties = new VideoStatusProperties();
            properties.setAsyncEnabled(true);
            properties.setSchedulingEnabled(true);
            properties.setFlushDirtyBatchSize(BATCH);
            properties.setFlushEventBatchSize(1_000);
            properties.setRoundBudgetMs(5_000);

            VideoStatusDirtyRecoveryScheduler recovery =
                    new VideoStatusDirtyRecoveryScheduler(
                            properties,
                            scopedMapper(Set.copyOf(vids), firstVid),
                            sets,
                            clock
                    );
            VideoStatusBatchFlushScheduler flush =
                    new VideoStatusBatchFlushScheduler(
                            properties,
                            sets,
                            flushService,
                            consumedEventMapper,
                            vidMutex
                    );

            // 初始：250 个 pending，dirty 全部丢失，DB 基线全为 0。
            assertTrue(dirtyMembers(sets).isEmpty(), "the test-specific dirty Set starts empty");
            assertEquals(TOTAL, statusCount(firstVid, lastVid, 1));
            assertEquals(0, statusCount(firstVid, lastVid, 2));
            assertEquals(0, statusCount(firstVid, lastVid, 3));
            assertEquals(TOTAL, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM video_status WHERE vid BETWEEN ? AND ? AND play_times = 0",
                    Integer.class, firstVid, lastVid));

            // ---------------- 四轮真实恢复：头部 SADD 持续失败，尾部健康 ----------------
            for (int round = 0; round < 4; round++) {
                recovery.recoverDirtyVids();
            }

            assertEquals(tail, dirtyMembers(sets),
                    "all 150 healthy tail vids must get a real dirty opportunity within four rounds");
            assertEquals(head, sets.attemptedHeadVids(),
                    "the failing head must also get its first real SADD opportunity");
            assertEquals(HEAD, statusCount(firstVid, lastVid, 1) - TAIL,
                    "the failing head must stay pending after the four recovery rounds");

            // ---------------- 真实落库健康尾部：DB 计数逐个等于独立 oracle ----------------
            int flushRounds = 0;
            while (statusCount(firstVid, lastVid, 1) > HEAD && flushRounds++ < 10) {
                flush.flushDirtyVideos();
            }
            assertEquals(0, pendingStatus(tail), "the healthy tail must converge through real flush");
            assertEquals(TAIL, statusCount(firstVid, lastVid, 2));
            for (Integer vid : tail) {
                assertEquals(oracle.get(vid).longValue(), baseline(vid),
                        "tail vid " + vid + " must equal its independent event-sum oracle");
            }

            // 故障头部：仍待刷、未被落库、未转人工修复（status 3）。
            assertEquals(HEAD, pendingStatus(head),
                    "the failing head must remain pending, not silently flushed");
            assertEquals(0, statusCount(firstVid, lastVid, 3),
                    "a Redis SADD failure must not quarantine events as status 3");
            for (Integer vid : head) {
                assertEquals(0L, baseline(vid),
                        "the failing head must not be written to DB before recovery");
            }

            // ---------------- 释放故障：越过退避后头部重新获得 dirty 并最终收敛 ----------------
            sets.releaseHeadFailure();
            clock.advance(BACKOFF_ADVANCE);

            int recoveryRounds = 0;
            while (dirtyMembers(sets).size() < HEAD && recoveryRounds++ < 10) {
                recovery.recoverDirtyVids();
            }
            assertEquals(head, dirtyMembers(sets),
                    "after the failure is released the head must be recovered into dirty again");

            flushRounds = 0;
            while (pendingStatus(head) > 0 && flushRounds++ < 10) {
                flush.flushDirtyVideos();
            }

            assertEquals(0, statusCount(firstVid, lastVid, 1), "no pending event may be left");
            assertEquals(TOTAL, statusCount(firstVid, lastVid, 2),
                    "all 250 videos must converge to FLUSHED");
            assertEquals(0, statusCount(firstVid, lastVid, 3),
                    "no event may be quarantined during the whole scenario");

            long oracleTotal = 0L;
            long dbTotal = 0L;
            for (Integer vid : vids) {
                assertEquals(oracle.get(vid).longValue(), baseline(vid),
                        "vid " + vid + " must equal its independent event-sum oracle");
                oracleTotal += oracle.get(vid);
                dbTotal += baseline(vid);
            }
            assertEquals(oracleTotal, dbTotal,
                    "final DB total must equal the independent event-sum oracle");
            System.out.printf(java.util.Locale.ROOT,
                    "T11 real convergence vids=%d head=%d tail=%d flushRounds=%d oracleTotal=%d%n",
                    TOTAL, HEAD, TAIL, flushRounds, oracleTotal);
        } finally {
            // 只删除本用例自己创建的专属 dirty key。
            redis.delete(dirtyKey);
        }
    }

    // ------------------------------------------------------------ seed / oracle

    /** 只新增独立用户、250 个新视频、status=0 基线及各自一条 status=1 的 +1 事件。 */
    private Seeded seedVideosAndPendingEvents() {
        String username = "t11-real-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')",
                username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);

        List<Object[]> videoArgs = new ArrayList<>(TOTAL);
        for (int i = 0; i < TOTAL; i++) {
            videoArgs.add(new Object[]{uid});
        }
        jdbc.batchUpdate("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 'isolated-t11-real', 'test', 'test', '', '', '', '')", videoArgs);

        List<Integer> vids = jdbc.queryForList(
                "SELECT vid FROM video WHERE uid=? ORDER BY vid", Integer.class, uid);
        assertEquals(TOTAL, vids.size(), "each test must seed its own independent 250 videos");

        List<Object[]> statusArgs = new ArrayList<>(TOTAL);
        for (Integer vid : vids) {
            statusArgs.add(new Object[]{vid});
        }
        jdbc.batchUpdate("INSERT INTO video_status(vid,play_times) VALUES (?,0)", statusArgs);

        List<Object[]> eventArgs = new ArrayList<>(TOTAL);
        for (Integer vid : vids) {
            eventArgs.add(pendingEventArgs(vid));
        }
        jdbc.batchUpdate("INSERT INTO video_status_consumed_event"
                + "(event_id,vid,event_type,delta,payload,payload_hash,process_status) "
                + "VALUES (?,?,?,?,?,?,?)", eventArgs);
        return new Seeded(vids);
    }

    private Map<Integer, Long> independentOracle(int firstVid, int lastVid) {
        Map<Integer, Long> oracle = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT vid, SUM(delta) AS total FROM video_status_consumed_event "
                        + "WHERE vid BETWEEN ? AND ? GROUP BY vid",
                firstVid, lastVid)) {
            oracle.put(((Number) row.get("vid")).intValue(),
                    ((Number) row.get("total")).longValue());
        }
        return oracle;
    }

    private static Object[] pendingEventArgs(int vid) {
        return new Object[]{
                UUID.randomUUID().toString(),
                vid,
                "PLAY",
                1L,
                "{}",
                "0".repeat(64),
                1
        };
    }

    // ------------------------------------------------------------ assertions

    private long baseline(int vid) {
        return jdbc.queryForObject(
                "SELECT play_times FROM video_status WHERE vid=?", Long.class, vid);
    }

    private int statusCount(int firstVid, int lastVid, int status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event "
                        + "WHERE process_status=? AND vid BETWEEN ? AND ?",
                Integer.class, status, firstVid, lastVid);
    }

    private int pendingStatus(Collection<Integer> vids) {
        return statusCount(vids, 1);
    }

    private int statusCount(Collection<Integer> vids, int status) {
        if (vids.isEmpty()) {
            return 0;
        }
        String placeholders = vids.stream().map(vid -> "?").collect(Collectors.joining(","));
        Object[] args = new Object[vids.size() + 1];
        args[0] = status;
        int index = 1;
        for (Integer vid : vids) {
            args[index++] = vid;
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event "
                + "WHERE process_status=? AND vid IN (" + placeholders + ")", Integer.class, args);
    }

    private Set<Integer> dirtyMembers(ScopedDirtySetOperations sets) {
        return sets.members(RedisKeyUtils.dirtyVideo()).stream()
                .map(Integer::valueOf)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    // ------------------------------------------------------------ test doubles

    /**
     * 只把“待刷视频扫描”限制在本用例自己新建的 vid 上，底层仍调用真实 Mapper/SQL。
     * 这样既不会扫描或推进历史 pending 行，也不会让历史数据影响四轮公平性断言。
     */
    private VideoStatusConsumedEventMapper scopedMapper(Set<Integer> ourVids, int firstVid) {
        return (VideoStatusConsumedEventMapper) Proxy.newProxyInstance(
                VideoStatusConsumedEventMapper.class.getClassLoader(),
                new Class<?>[]{VideoStatusConsumedEventMapper.class},
                (proxy, method, args) -> {
                    if ("selectPendingVidsAfter".equals(method.getName())) {
                        Integer after = (Integer) args[0];
                        int limit = (Integer) args[1];
                        int scopedAfter = after == null
                                ? firstVid - 1
                                : Math.max(after, firstVid - 1);
                        List<Integer> raw = consumedEventMapper
                                .selectPendingVidsAfter(scopedAfter, limit);
                        return raw.stream().filter(ourVids::contains).toList();
                    }
                    try {
                        return method.invoke(consumedEventMapper, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * 真实 Redis Set 操作的测试作用域包装器：只把 dirty key 映射到本用例专属 key，
     * 并且只对本用例的头部 vid 注入 SADD 失败。其它 key / 其它 vid 全部委托真实实现。
     */
    static final class ScopedDirtySetOperations extends RedisSetOperations {

        private final String dirtyKey;
        private final Set<Integer> failingHeadVids;
        private final Set<Integer> attemptedHeadVids = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean failHead = new AtomicBoolean(true);

        ScopedDirtySetOperations(
                StringRedisTemplate redisTemplate,
                JsonUtils jsonUtils,
                String dirtyKey,
                Set<Integer> failingHeadVids
        ) {
            super(redisTemplate, jsonUtils);
            this.dirtyKey = dirtyKey;
            this.failingHeadVids = Set.copyOf(failingHeadVids);
        }

        @Override
        public long add(String key, String... values) {
            if (failHead.get() && RedisKeyUtils.dirtyVideo().equals(key)) {
                for (String value : values) {
                    int vid = Integer.parseInt(value);
                    if (failingHeadVids.contains(vid)) {
                        attemptedHeadVids.add(vid);
                        throw new IllegalStateException(
                                "simulated Redis SADD failure for head vid " + vid);
                    }
                }
            }
            return super.add(scoped(key), values);
        }

        @Override
        public Set<String> pop(String key, long count) {
            return super.pop(scoped(key), count);
        }

        @Override
        public boolean isMember(String key, String value) {
            return super.isMember(scoped(key), value);
        }

        @Override
        public Set<String> members(String key) {
            return super.members(scoped(key));
        }

        @Override
        public long size(String key) {
            return super.size(scoped(key));
        }

        @Override
        public long remove(String key, String... values) {
            return super.remove(scoped(key), values);
        }

        Set<Integer> attemptedHeadVids() {
            return Set.copyOf(attemptedHeadVids);
        }

        void releaseHeadFailure() {
            failHead.set(false);
        }

        private String scoped(String key) {
            return RedisKeyUtils.dirtyVideo().equals(key) ? dirtyKey : key;
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicLong millis =
                new AtomicLong(Instant.parse("2026-10-22T00:00:00Z").toEpochMilli());

        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    private record Seeded(List<Integer> vids) {
    }
}
