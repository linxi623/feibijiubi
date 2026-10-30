package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.videostats.mq.VideoStatusDirtyRecoveryScheduler;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * T11 真实 MySQL 隔离验收：真实 {@code selectPendingVidsAfter} SQL、跨轮游标与回绕。
 *
 * <p>显式 {@code -DvideoStatusIT=true} 才运行，并且复用
 * {@link VideoStatusSqlBaselineTests.IsolationGuard}：只允许隔离 MySQL
 * {@code 127.0.0.1:13307/feibijiubi}，不加载 {@code application.yml}，也不启动
 * Redis/RabbitMQ。恢复调度器的 {@link RedisSetOperations} 用测试桩模拟 SADD 成功/失败，
 * 因此本类只验证“真实 SQL 扫描顺序 + 调度器游标”，不连接任何 Redis。</p>
 *
 * <p>数据隔离：每个用例只新增自己的用户、视频与 status=1 消费事件，不依赖固定 vid、
 * 不清空表、不删除其它测试数据。历史 pending 行只可能位于新 vid 之前；公平性用例先按
 * 真实批量大小把游标预热过这些历史行，再开始计四轮，避免旧数据干扰 T11 断言。</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusDirtyRecoveryFairnessIsolatedTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusSqlBaselineTests.IsolationGuard.class)
class VideoStatusDirtyRecoveryFairnessIsolatedTests {

    private static final int BATCH = 100;
    private static final int HEAD = 100;
    private static final int TAIL = 150;
    private static final int TOTAL = HEAD + TAIL;

    /** DataSource + JdbcTemplate + MyBatis mappers only: no Redis, no RabbitMQ, no scheduled tasks. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
    @MapperScan("com.feibijiubi.backend.videostats.mapper")
    static class TestApplication {
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    VideoStatusConsumedEventMapper consumedEventMapper;

    /**
     * 真实 SQL 契约：严格 {@code vid > afterVid}、按 vid 升序、DISTINCT、LIMIT 生效。
     * 不假设表中只有本用例数据，只断言本用例新增 vid 的相对顺序。
     */
    @Test
    void realSelectPendingVidsAfterUsesStrictKeysetOrderingAndLimit() {
        List<Integer> vids = seedVideosAndPendingEvents();
        int headMax = vids.get(HEAD - 1);
        int tailMax = vids.get(TOTAL - 1);

        List<Integer> all = consumedEventMapper.selectPendingVidsAfter(null, 100_000);
        assertTrue(all.containsAll(vids), "without a cursor all newly seeded pending vids must be visible");
        assertEquals(all.stream().distinct().count(), all.size(), "DISTINCT vid is required");
        assertEquals(all.stream().sorted().toList(), all, "SQL must return vid ascending");

        List<Integer> afterHead = consumedEventMapper.selectPendingVidsAfter(headMax, 100_000);
        assertTrue(afterHead.stream().allMatch(vid -> vid > headMax),
                "keyset predicate must be strict: vid > afterVid");
        assertTrue(afterHead.containsAll(vids.subList(HEAD, TOTAL)),
                "the healthy tail must be visible after the failing head cursor");

        List<Integer> limited = consumedEventMapper.selectPendingVidsAfter(null, 7);
        assertTrue(limited.size() <= 7, "LIMIT must be honoured, got " + limited.size());
        assertEquals(limited.stream().sorted().toList(), limited);

        int newVid = seedExtraVideoAndPendingEvent();
        assertTrue(newVid > tailMax, "a newly inserted video must sort after this test's seeded range");
        List<Integer> afterTail = consumedEventMapper.selectPendingVidsAfter(tailMax, 100_000);
        assertTrue(afterTail.contains(newVid), "a pending vid after the cursor must be returned");
        assertTrue(afterTail.stream().allMatch(vid -> vid > tailMax));
        assertFalse(consumedEventMapper.selectPendingVidsAfter(newVid, 100_000).contains(newVid),
                "the cursor row itself must not be returned again");
    }

    /**
     * 计划验收场景：250 个 pending、批量 100，前 100 个持续失败，后 150 个健康。
     * 真实 SQL + 真实调度器必须在不超过四轮内让 150 个健康 vid 全部得到 SADD 机会，
     * 并在游标到达末尾后回绕，让失败头部再次获得机会（此时已越过本地退避）。
     */
    @Test
    void realSqlCursorReachesHealthyTailWithinFourRoundsAndWraps() {
        List<Integer> vids = seedVideosAndPendingEvents();
        Set<Integer> head = new TreeSet<>(vids.subList(0, HEAD));
        Set<Integer> tail = new TreeSet<>(vids.subList(HEAD, TOTAL));
        int firstVid = vids.get(0);
        int lastVid = vids.get(TOTAL - 1);

        MutableClock clock = new MutableClock();
        Set<Integer> attemptedHead = new TreeSet<>();
        Set<Integer> recoveredTail = new TreeSet<>();
        RedisSetOperations sets = mock(RedisSetOperations.class);
        doAnswer(call -> {
            int vid = vid(call.getArgument(1));
            if (head.contains(vid)) {
                // 前 100 个持续失败：SADD 抛错，dirty 保持缺失，事件仍是 status=1。
                attemptedHead.add(vid);
                throw new IllegalStateException("simulated Redis failure for head vid " + vid);
            }
            if (tail.contains(vid)) {
                recoveredTail.add(vid);
            }
            return 1L;
        }).when(sets).add(eq(RedisKeyUtils.dirtyVideo()), any(String[].class));

        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setSchedulingEnabled(true);
        properties.setFlushDirtyBatchSize(BATCH);
        VideoStatusDirtyRecoveryScheduler scheduler =
                new VideoStatusDirtyRecoveryScheduler(properties, consumedEventMapper, sets, clock);

        // 历史 pending 只会在新 vid 之前。用真实批量把游标推进到“剩余历史行 < 100”，
        // 再开始四轮公平性计时；不删除、不改写任何历史行。
        int foreignBelow = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT vid) FROM video_status_consumed_event "
                        + "WHERE process_status = 1 AND vid < ?",
                Integer.class, firstVid);
        int warmupRounds = foreignBelow / BATCH;
        for (int i = 0; i < warmupRounds; i++) {
            scheduler.recoverDirtyVids();
        }

        int rounds = 0;
        while (rounds < 4 && !recoveredTail.containsAll(tail)) {
            scheduler.recoverDirtyVids();
            rounds++;
        }

        assertTrue(rounds <= 4, "healthy tail must be reached within 4 rounds, rounds=" + rounds);
        assertEquals(tail, recoveredTail,
                "all 150 healthy vids must get a real dirty-recovery opportunity");
        assertEquals(head, attemptedHead,
                "the failing head must also get its first real SADD opportunity within the four rounds");
        verify(sets, never()).remove(any(String.class), any(String[].class));

        // 游标此时应停在本用例最大 vid。历史/其它测试若在更高 vid 留下 pending，
        // 会破坏“回绕”前提；本用例自己的新数据不会高于 lastVid。
        int pendingAbove = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT vid) FROM video_status_consumed_event "
                        + "WHERE process_status = 1 AND vid > ?",
                Integer.class, lastVid);
        assertEquals(0, pendingAbove,
                "this test only proves wrap when no newer pending row exists above its cursor");

        // 越过 2 秒本地退避后，再执行“回绕查询 + 从最小 vid 重新扫描”所需的轮数。
        // 若不回绕，vid > lastVid 恒为空，失败头部永远不会再次被尝试。
        clock.advance(Duration.ofSeconds(3));
        attemptedHead.clear();
        int wrapRounds = foreignBelow / BATCH + 1;
        for (int i = 0; i < wrapRounds && attemptedHead.isEmpty(); i++) {
            scheduler.recoverDirtyVids();
        }
        assertFalse(attemptedHead.isEmpty(),
                "cursor must wrap after the last vid and retry the pending head");
        verify(sets, never()).remove(any(String.class), any(String[].class));
    }

    // ------------------------------------------------------------ seed / helpers

    /** 只新增独立用户、250 个新视频及其 status=1 事件，返回按 vid 升序的新 vid 列表。 */
    private List<Integer> seedVideosAndPendingEvents() {
        String username = "t11-" + UUID.randomUUID().toString().substring(0, 16);
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')",
                username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);

        List<Object[]> videoArgs = new ArrayList<>(TOTAL);
        for (int i = 0; i < TOTAL; i++) {
            videoArgs.add(new Object[]{uid});
        }
        jdbc.batchUpdate("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 'isolated-t11', 'test', 'test', '', '', '', '')", videoArgs);

        List<Integer> vids = jdbc.queryForList(
                "SELECT vid FROM video WHERE uid=? ORDER BY vid", Integer.class, uid);
        assertEquals(TOTAL, vids.size(), "each test must seed its own independent 250 videos");

        List<Object[]> eventArgs = new ArrayList<>(TOTAL);
        for (Integer vid : vids) {
            eventArgs.add(pendingEventArgs(vid));
        }
        jdbc.batchUpdate("INSERT INTO video_status_consumed_event"
                + "(event_id,vid,event_type,delta,payload,payload_hash,process_status) "
                + "VALUES (?,?,?,?,?,?,?)", eventArgs);
        return vids;
    }

    private int seedExtraVideoAndPendingEvent() {
        String username = "t11-" + UUID.randomUUID().toString().substring(0, 16);
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')",
                username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 'isolated-t11-extra', 'test', 'test', '', '', '', '')", uid);
        int newVid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status_consumed_event"
                        + "(event_id,vid,event_type,delta,payload,payload_hash,process_status) "
                        + "VALUES (?,?,?,?,?,?,?)",
                pendingEventArgs(newVid));
        return newVid;
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

    private static int vid(Object rawValue) {
        // Mockito 对 varargs 的 Answer 参数既可能是 String，也可能是 String[]。
        if (rawValue instanceof String[] values) {
            return Integer.parseInt(values[0]);
        }
        return Integer.parseInt((String) rawValue);
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
}
