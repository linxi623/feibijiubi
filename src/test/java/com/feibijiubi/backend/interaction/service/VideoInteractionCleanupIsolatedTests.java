package com.feibijiubi.backend.interaction.service;

import com.feibijiubi.backend.interaction.config.VideoInteractionProperties;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.entity.VideoStatus;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.interaction.mapper.VideoInteractionMapper;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionCleanupServiceImpl;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionServiceImpl;
import com.feibijiubi.backend.videostats.service.VideoStatusService;
import com.feibijiubi.backend.videostats.service.VideoStatusIsolatedIntegrationTests;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T15 有界清理的真实隔离 IT：MySQL 13307 + Redis 16379（本类实际只用 MySQL）。
 *
 * <p>只在显式 JVM 参数 {@code -DvideoStatusIT=true} 时运行，复用
 * {@link VideoStatusIsolatedIntegrationTests.IsolationGuard} 与 {@code video-status-isolated.properties}，
 * 不加载 application.yml、不连其它环境。<b>本轮只编译、未运行</b>（没有启动隔离栈）。</p>
 *
 * <h2>为什么这是“全局清理”的测试，仍然不会误删别人的数据</h2>
 * <p>生产清理没有 vid 作用域（它必须扫全表才能推进），所以本类<b>不能</b>给自己划一个 vid 边界。
 * 每个用例结束时还会把本类剩下的过期候选删干净（同样只限本类 id 集合），保证下一个用例从干净基线开始。
 * 反过来，本类也不会偷偷清理未知测试数据：每一轮真正执行清理之前都先调用
 * {@link #assertOnlyOwnExpiredCandidates()}，只要隔离库里存在<b>不是本类这次造出来</b>的过期候选行
 * （请求行或窗口行），用例就<b>立即失败退出</b>，不执行任何 DELETE。真出现这种情况时的处理方式写在
 * 失败消息里：由操作者确认后在全新的隔离库上重跑，而不是让测试替人决定删哪些历史数据。
 * 本类也从不清空表、不删别人的行。</p>
 *
 * <h2>覆盖的矩阵</h2>
 * <ol>
 *   <li>过期/新鲜/PENDING/{@code completed_at IS NULL}/{@code last_counted_at IS NULL} 的真实 SQL 矩阵；</li>
 *   <li>删掉过期窗口行后，“同一 actor 立刻计数一次、紧接着第二次被抑制”的 30 分钟窗口仍然成立；</li>
 *   <li>记录还在（29 天）→ 重放历史结果；记录被删 → 同一 {@code requestKey} 变成新请求（仍受窗口约束）；</li>
 *   <li>“先选 id、再推进时间、再删”返回 0 行（守卫），<b>以及</b>真实行锁交错下不删刚推进的窗口行；</li>
 *   <li>批大小有界 + 多轮收敛 + 重跑幂等；</li>
 *   <li>两条候选查询的 {@code EXPLAIN} 命中新索引且没有 filesort；</li>
 *   <li>不碰 {@code video_status} / Outbox / 消费账本。</li>
 * </ol>
 *
 * <p>运行：{@code ./mvnw -DvideoStatusIT=true -Dtest=VideoInteractionCleanupIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoInteractionCleanupIsolatedTests.TestApplication.class,
        properties = {
                "spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none",
                "app.video-status.scheduling-enabled=false",
                "app.video-status.outbox-relay-enabled=false",
                // 同步计数：窗口推进直接落 video_status，用例不需要 Outbox/消费者。
                "app.video-status.async-enabled=false",
                "app.video-interaction.cleanup-enabled=true",
                "app.video-interaction.cleanup-batch-size=3",
                "app.video-interaction.cleanup-fixed-delay-ms=60000"
        })
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VideoInteractionCleanupIsolatedTests {

    /** 收敛轮次上限：批大小 3、本类最多造 2*batch+1 = 7 条，3 轮足够；留出余量。 */
    private static final int MAX_ROUNDS = 20;
    private static final int BATCH_SIZE = 3;
    private static final int RETENTION_DAYS = 30;

    @Configuration(proxyBeanMethods = false)
    @Profile("video-status-isolated")
    @EnableAutoConfiguration(excludeName =
            "org.redisson.spring.starter.RedissonAutoConfigurationV2")
    @EnableConfigurationProperties({VideoStatusProperties.class, VideoInteractionProperties.class})
    // R3：交互 mapper 在 com.feibijiubi.backend.interaction.mapper。
    // R4：统计 mapper 迁到 videostats.mapper，旧 com.feibijiubi.backend.mapper 已无接口，
    // 该扫描项按等价集合改为 videostats.mapper（不是缩小扫描范围）。
    @MapperScan({"com.feibijiubi.backend.videostats.mapper", "com.feibijiubi.backend.interaction.mapper"})
    @Import({VideoInteractionServiceImpl.class,
            VideoInteractionCleanupServiceImpl.class})
    static class TestApplication {

        /** 同步路径不创建统计事件：本类只用同步计数验证窗口行为。 */
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
                    throw new AssertionError("清理用例不得读取统计缓存");
                }
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired VideoInteractionService interactionService;
    @Autowired VideoInteractionCleanupService cleanupService;
    @Autowired VideoInteractionMapper interactionMapper;
    @Autowired VideoInteractionProperties properties;
    @Autowired VideoStatusProperties videoStatusProperties;
    @Autowired PlatformTransactionManager transactionManager;

    int vid;

    /** 本类这一次造出来、允许被清理删掉的请求行 id。 */
    private final Set<Long> ownRequestIds = new HashSet<>();
    /** 本类这一次造出来、允许被清理删掉的窗口行 id。 */
    private final Set<Long> ownWindowIds = new HashSet<>();

    /**
     * 用例结束后用<b>本类跟踪的 id 集合</b>把剩下的过期候选删干净，保证下一个用例从干净基线开始。
     *
     * <p><b>绝不调用全局 {@code cleanupOnce()}</b>：{@code @AfterEach} 会在 {@code @BeforeEach} 失败或
     * {@link #assertOnlyOwnExpiredCandidates()} 拒绝的用例上照样执行；那时代理如果跑全局清理，就会把
     * 被拒的那些“别人的过期行”删掉——恰好违反本类与文档给出的承诺（不删未知测试数据）。这里两条 DELETE
     * 都是主键等值 + **时间/状态守卫**（与生产删除语句相同），未跟踪的 id 一条也不会碰到；行已被推进或
     * 离开终态时影响行数为 0（守卫路径，不是错误）。</p>
     *
     * <p><b>跟踪完整性</b>：能进入 30 天候选窗口的行只有 {@link #seedRequest}、{@link #seedPendingRequest}、
     * {@link #seedTerminalWithoutCompletionTime}、{@link #seedWindowFor} 与 {@link #ageRequestToDaysAgo}
     * 造出的行，它们都登记了 id；{@code record(...)} 新建的行时间都是“现在”，保留期内不可能成为候选。
     * 运行期由 {@link #assertOnlyOwnExpiredCandidates()} 兜底：任何未被跟踪的过期候选都会被当成外来
     * 数据让用例失败，而不会被静默删除。</p>
     */
    @AfterEach
    void drainOwnExpiredCandidatesByIdSet() {
        if (!ownRequestIds.isEmpty()) {
            interactionMapper.deleteExpiredRequestsByIds(List.copyOf(ownRequestIds), RETENTION_DAYS);
        }
        if (!ownWindowIds.isEmpty()) {
            interactionMapper.deleteIdleWindowsByIds(List.copyOf(ownWindowIds), RETENTION_DAYS);
        }
    }

    @BeforeEach
    void seedOwnVideo() {
        assertTrue(properties.isCleanupEnabled(), "本类必须显式开启清理");
        assertEquals(BATCH_SIZE, properties.getCleanupBatchSize());
        assertFalse(videoStatusProperties.isAsyncEnabled(), "本类验证同步计数路径");
        ownRequestIds.clear();
        ownWindowIds.clear();

        String username = "t15-it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) "
                + "VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject(
                "SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 't15-cleanup-isolated', 'test', 'test', '', '', '', '')", uid);
        vid = jdbc.queryForObject(
                "SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times) VALUES (?,0)", vid);
    }

    // ----------------------------------------------------- 过期/新鲜/未完成矩阵

    @Test
    void deletesOnlyExpiredTerminalRequests() {
        long expiredCounted = seedRequest(1, -31);
        long expiredSuppressed = seedRequest(2, -31);
        long freshCounted = seedRequest(1, -29);
        long freshSuppressed = seedRequest(2, -29);
        long pending = seedPendingRequest(-40);
        long terminalWithoutCompletion = seedTerminalWithoutCompletionTime(1);

        runCleanupUntilOwnCandidatesGone();

        assertTrue(!requestExists(expiredCounted), "31 天前的终态行必须被删");
        assertTrue(!requestExists(expiredSuppressed));
        assertTrue(requestExists(freshCounted), "29 天前的行必须保留");
        assertTrue(requestExists(freshSuppressed));
        assertTrue(requestExists(pending), "PENDING 行永不清理（放宽它就是改幂等语义）");
        assertTrue(requestExists(terminalWithoutCompletion),
                "终态但 completed_at 为 NULL 的行无法证明保留期，永不清理");
    }

    @Test
    void neverDeletesRowsThatCannotProveTheirRetention() {
        long pending = seedPendingRequest(-40);
        long terminalWithoutCompletion = seedTerminalWithoutCompletionTime(2);

        assertOnlyOwnExpiredCandidates();
        VideoInteractionCleanupService.RoundResult result = cleanupService.cleanupOnce();

        assertEquals(0, result.totalDeleted(), "没有可证明过期的候选时必须零删除");
        assertTrue(requestExists(pending));
        assertTrue(requestExists(terminalWithoutCompletion));
    }

    @Test
    void deletesOnlyWindowsIdleForTheWholeRetentionPeriod() {
        long expired = seedWindow(-31);
        long fresh = seedWindow(-29);
        long neverCounted = seedWindow(null);

        runCleanupUntilOwnCandidatesGone();

        assertTrue(!windowExists(expired), "连续 30 天没有计数的窗口行必须被删");
        assertTrue(windowExists(fresh), "29 天前计过数的窗口行必须保留");
        assertTrue(windowExists(neverCounted),
                "last_counted_at IS NULL 永不清理：删掉它等于把窗口重置，下一次请求必然计数");
    }

    // ------------------------------------------------------------ 语义恢复矩阵

    @Test
    void deletedIdleWindowIsRecreatedAndStillSuppressesTheSecondRequest() {
        String actorHash = randomActorHash();
        // 先给这个 actor 一段“连续 31 天没有计数”的窗口历史，再让清理把它删掉。
        seedWindowFor(actorHash, -31);
        runCleanupUntilOwnCandidatesGone();
        assertEquals(0, windowCountFor(actorHash), "过期窗口行必须已被删除");

        VideoInteractionResult first = record(actorHash, randomRequestKey());
        VideoInteractionResult second = record(actorHash, randomRequestKey());

        assertTrue(first.counted(), "窗口行被删后第一次请求必须计数（否则限计窗口被永久锁死）");
        assertFalse(second.counted(), "紧接着的第二次请求必须被 30 分钟窗口抑制");
        assertEquals(VideoInteractionService.STATE_SUPPRESSED, second.state());
    }

    @Test
    void deletedRequestKeyBecomesNewRequestWhilePresentRowStillReplays() {
        String actorHash = randomActorHash();

        // 1) 仍在保留期内的行：重放历史结果，不新增计数。
        VideoInteractionResult first = record(actorHash, randomRequestKey());
        assertTrue(first.counted());
        ageRequestToDaysAgo(first.requestKey(), -29);
        runCleanupUntilOwnCandidatesGone();
        VideoInteractionResult replay = record(actorHash, first.requestKey());
        assertTrue(replay.duplicate(), "记录还在就必须重放");
        assertTrue(replay.counted());
        assertEquals(first.state(), replay.state());

        // 2) 记录被清理之后：同一 requestKey 变成新请求，但仍受 30 分钟窗口约束。
        ageRequestToDaysAgo(first.requestKey(), -31);
        runCleanupUntilOwnCandidatesGone();
        assertEquals(0, requestCount(first.requestKey()), "31 天前的记录必须已被清理");
        VideoInteractionResult afterCleanup = record(actorHash, first.requestKey());
        assertFalse(afterCleanup.duplicate(), "记录已删，同一 requestKey 不再是重放");
        assertFalse(afterCleanup.counted(), "窗口仍在 30 分钟内，新请求必须被抑制");
        assertEquals(VideoInteractionService.STATE_SUPPRESSED, afterCleanup.state());
    }

    // ---------------------------------------------------------------- 守卫（确定性）

    @Test
    void deletingAfterTheCandidateWasAdvancedDeletesZeroRows() {
        String actorHash = randomActorHash();
        long windowId = seedWindowFor(actorHash, -31);
        assertOnlyOwnExpiredCandidates();

        List<Long> candidates = interactionMapper.selectIdleWindowIds(RETENTION_DAYS, BATCH_SIZE);
        assertTrue(candidates.contains(windowId), "过期窗口行必须出现在候选里");

        // 模拟“候选读和 DELETE 之间被 record 推进了窗口”。
        jdbc.update("UPDATE video_interaction_window SET last_counted_at = NOW(3) WHERE id = ?",
                windowId);
        int deleted = interactionMapper.deleteIdleWindowsByIds(List.of(windowId), RETENTION_DAYS);

        assertEquals(0, deleted, "时间守卫必须拦下刚被推进的窗口行");
        assertTrue(windowExists(windowId), "刚推进的窗口行必须仍然存在，否则 30 分钟限计被绕过");
    }

    @Test
    void deletingAfterTheCandidateLeftTerminalStateDeletesZeroRows() {
        long requestId = seedRequest(1, -31);
        String requestKey = requestKeyOf(requestId);
        assertOnlyOwnExpiredCandidates();

        List<Long> candidates = interactionMapper.selectExpiredRequestIds(1, RETENTION_DAYS, BATCH_SIZE);
        assertTrue(candidates.contains(requestId));

        jdbc.update("UPDATE video_interaction_request SET state = 0, completed_at = NULL "
                + "WHERE id = ?", requestId);
        int deleted = interactionMapper.deleteExpiredRequestsByIds(List.of(requestId), RETENTION_DAYS);

        assertEquals(0, deleted, "状态/时间守卫必须拦下已离开终态的行");
        assertEquals(1, requestCount(requestKey));
    }

    @Test
    void deleteWithEmptyIdCollectionDeletesNothing() {
        long requestId = seedRequest(1, -31);
        long windowId = seedWindow(-31);

        assertEquals(0, interactionMapper.deleteExpiredRequestsByIds(List.of(), RETENTION_DAYS));
        assertEquals(0, interactionMapper.deleteIdleWindowsByIds(List.of(), RETENTION_DAYS));

        assertTrue(requestExists(requestId), "空集合必须 fail closed：一条都不能删");
        assertTrue(windowExists(windowId));
    }

    // ------------------------------------------------------- 真实行锁交错（并发）

    @Test
    void concurrentlyAdvancedWindowSurvivesCleanup() throws Exception {
        String actorHash = randomActorHash();
        long windowId = seedWindowFor(actorHash, -31);

        assertOnlyOwnExpiredCandidates();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch lockHeld = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        // 真实行锁：与 record 的 selectWindowForUpdate 同一把锁。
                        jdbc.queryForObject("SELECT id FROM video_interaction_window "
                                + "WHERE id = ? FOR UPDATE", Long.class, windowId);
                        jdbc.update("UPDATE video_interaction_window "
                                + "SET last_counted_at = NOW(3) WHERE id = ?", windowId);
                        lockHeld.countDown();
                        // 持锁一小段时间，让清理那条 DELETE 真实地等在行锁上；然后提交。
                        // 不能在这里等清理完成：DELETE 要等本事务提交才能继续，等下去就是死等。
                        sleepQuietly(800);
                    }));
            assertTrue(lockHeld.await(10, TimeUnit.SECONDS), "未能在 10 秒内拿到窗口行锁");

            Future<VideoInteractionCleanupService.RoundResult> cleanup =
                    pool.submit(cleanupService::cleanupOnce);

            holder.get(30, TimeUnit.SECONDS);
            VideoInteractionCleanupService.RoundResult result = cleanup.get(30, TimeUnit.SECONDS);

            assertEquals(0, result.windowsDeleted(),
                    "候选来自旧快照，但 DELETE 的守卫必须让刚推进的窗口行 0 行");
            assertTrue(windowExists(windowId), "真实锁交错后窗口行必须仍然存在");
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------- 批大小 / 收敛 / 幂等

    @Test
    void batchIsBoundedAndConvergesOverRounds() {
        for (int i = 0; i < 2 * BATCH_SIZE + 1; i++) {
            seedRequest(1, -31);
        }
        assertOnlyOwnExpiredCandidates();

        int rounds = 0;
        while (rounds < MAX_ROUNDS && ownExpiredCandidateCount() > 0) {
            VideoInteractionCleanupService.RoundResult result = cleanupService.cleanupOnce();
            assertTrue(result.countedRequestsDeleted() <= BATCH_SIZE,
                    "单轮删除数不得超过批大小");
            assertTrue(result.windowsDeleted() <= BATCH_SIZE);
            rounds++;
        }

        assertEquals(0, ownExpiredCandidateCount(), "清理必须在有限轮次内收敛");
        assertTrue(rounds >= 3, "2*batch+1 条候选不可能在一轮内删完");
    }

    @Test
    void rerunIsIdempotent() {
        seedRequest(1, -31);
        seedRequest(2, -31);
        seedWindow(-31);

        runCleanupUntilOwnCandidatesGone();
        assertOnlyOwnExpiredCandidates();
        VideoInteractionCleanupService.RoundResult second = cleanupService.cleanupOnce();

        assertEquals(0, second.totalDeleted(), "同一数据集重跑必须删 0 行且不抛异常");
        assertFalse(second.requestBatchFull());
        assertFalse(second.windowBatchFull());
    }

    // ------------------------------------------------------------------ 索引与副作用

    @Test
    void candidateQueriesUseTheIntendedIndexes() {
        Map<String, Object> requestPlan = jdbc.queryForMap(
                "EXPLAIN SELECT id FROM video_interaction_request "
                        + "FORCE INDEX (idx_video_interaction_request_state_completed) "
                        + "WHERE state = 1 AND completed_at IS NOT NULL "
                        + "AND completed_at <= TIMESTAMPADD(DAY, -30, NOW(3)) "
                        + "ORDER BY completed_at, id LIMIT 10");
        assertEquals("idx_video_interaction_request_state_completed", requestPlan.get("key"));
        assertFalse("ALL".equals(requestPlan.get("type")), "候选查询不得全表扫描");
        String requestExtra = String.valueOf(requestPlan.get("Extra"));
        assertFalse(requestExtra.toLowerCase().contains("using filesort"),
                "ORDER BY 必须由索引顺序满足，否则 LIMIT 无法提前终止");

        Map<String, Object> windowPlan = jdbc.queryForMap(
                "EXPLAIN SELECT id FROM video_interaction_window "
                        + "FORCE INDEX (idx_video_interaction_window_last_counted) "
                        + "WHERE last_counted_at IS NOT NULL "
                        + "AND last_counted_at <= TIMESTAMPADD(DAY, -30, NOW(3)) "
                        + "ORDER BY last_counted_at, id LIMIT 10");
        assertEquals("idx_video_interaction_window_last_counted", windowPlan.get("key"));
        assertFalse("ALL".equals(windowPlan.get("type")));
        assertFalse(String.valueOf(windowPlan.get("Extra")).toLowerCase()
                .contains("using filesort"));
    }

    @Test
    void cleanupDoesNotTouchStatisticsTables() {
        long requestId = seedRequest(1, -31);
        long windowId = seedWindow(-31);
        int outboxBefore = count("SELECT COUNT(*) FROM video_status_outbox WHERE aggregate_id = ?");
        int consumedBefore = count(
                "SELECT COUNT(*) FROM video_status_consumed_event WHERE vid = ?");
        long playTimesBefore = playTimes();

        runCleanupUntilOwnCandidatesGone();

        assertTrue(!requestExists(requestId));
        assertTrue(!windowExists(windowId));
        assertEquals(outboxBefore, count(
                "SELECT COUNT(*) FROM video_status_outbox WHERE aggregate_id = ?"));
        assertEquals(consumedBefore, count(
                "SELECT COUNT(*) FROM video_status_consumed_event WHERE vid = ?"));
        assertEquals(playTimesBefore, playTimes(), "清理不得改动业务计数");
    }

    // -------------------------------------------------------------------- helpers

    /**
     * 执行清理直到本类造出的过期候选全部消失。
     *
     * <p>先做“没有别人的过期候选”检查：生产清理是全局的，本类不能给自己划 vid 边界，
     * 所以宁可在这里失败退出，也不替人决定删哪些历史数据。</p>
     */
    private void runCleanupUntilOwnCandidatesGone() {
        assertOnlyOwnExpiredCandidates();
        int rounds = 0;
        while (rounds < MAX_ROUNDS && ownExpiredCandidateCount() > 0) {
            cleanupService.cleanupOnce();
            rounds++;
        }
        assertEquals(0, ownExpiredCandidateCount(), "清理未在 " + MAX_ROUNDS + " 轮内收敛");
    }

    /**
     * 隔离库里出现“不是本类这次造出来”的过期候选行时立刻失败，且<b>不执行任何删除</b>。
     */
    private void assertOnlyOwnExpiredCandidates() {
        List<Long> foreignRequests = expiredRequestIds().stream()
                .filter(id -> !ownRequestIds.contains(id))
                .toList();
        List<Long> foreignWindows = expiredWindowIds().stream()
                .filter(id -> !ownWindowIds.contains(id))
                .toList();
        if (!foreignRequests.isEmpty() || !foreignWindows.isEmpty()) {
            fail("隔离库里已经存在 " + foreignRequests.size() + " 条过期请求行与 "
                    + foreignWindows.size() + " 条过期窗口行不是本类造出的；"
                    + "生产清理是全局扫描，本类不会替操作者删除未知测试数据。"
                    + "请由操作者确认这些行可以删除后，在全新的隔离库上重跑本类");
        }
    }

    private int ownExpiredCandidateCount() {
        return (int) (expiredRequestIds().stream().filter(ownRequestIds::contains).count()
                + expiredWindowIds().stream().filter(ownWindowIds::contains).count());
    }

    private List<Long> expiredRequestIds() {
        return jdbc.queryForList(
                "SELECT id FROM video_interaction_request "
                        + "WHERE state IN (1, 2) AND completed_at IS NOT NULL "
                        + "AND completed_at <= TIMESTAMPADD(DAY, -30, NOW(3))",
                Long.class);
    }

    private List<Long> expiredWindowIds() {
        return jdbc.queryForList(
                "SELECT id FROM video_interaction_window "
                        + "WHERE last_counted_at IS NOT NULL "
                        + "AND last_counted_at <= TIMESTAMPADD(DAY, -30, NOW(3))",
                Long.class);
    }

    private long seedRequest(int state, int completedDaysAgo) {
        String requestKey = randomRequestKey();
        jdbc.update("INSERT INTO video_interaction_request"
                        + "(request_key, actor_type, actor_hash, vid, action, state, created_at, "
                        + "completed_at) VALUES (?, 'USER', ?, ?, 'PLAY', ?, "
                        + "TIMESTAMPADD(DAY, ?, NOW(3)), TIMESTAMPADD(DAY, ?, NOW(3)))",
                requestKey, randomActorHash(), vid, state,
                completedDaysAgo - 1, completedDaysAgo);
        long id = jdbc.queryForObject(
                "SELECT id FROM video_interaction_request WHERE request_key = ?",
                Long.class, requestKey);
        ownRequestIds.add(id);
        return id;
    }

    private long seedPendingRequest(int createdDaysAgo) {
        String requestKey = randomRequestKey();
        jdbc.update("INSERT INTO video_interaction_request"
                        + "(request_key, actor_type, actor_hash, vid, action, state, created_at) "
                        + "VALUES (?, 'USER', ?, ?, 'PLAY', 0, TIMESTAMPADD(DAY, ?, NOW(3)))",
                requestKey, randomActorHash(), vid, createdDaysAgo);
        long id = jdbc.queryForObject(
                "SELECT id FROM video_interaction_request WHERE request_key = ?",
                Long.class, requestKey);
        ownRequestIds.add(id);
        return id;
    }

    private long seedTerminalWithoutCompletionTime(int state) {
        String requestKey = randomRequestKey();
        jdbc.update("INSERT INTO video_interaction_request"
                        + "(request_key, actor_type, actor_hash, vid, action, state, created_at) "
                        + "VALUES (?, 'USER', ?, ?, 'PLAY', ?, TIMESTAMPADD(DAY, -40, NOW(3)))",
                requestKey, randomActorHash(), vid, state);
        long id = jdbc.queryForObject(
                "SELECT id FROM video_interaction_request WHERE request_key = ?",
                Long.class, requestKey);
        ownRequestIds.add(id);
        return id;
    }

    /** @param countedDaysAgo {@code null} = 从未计数（{@code last_counted_at IS NULL}） */
    private long seedWindow(Integer countedDaysAgo) {
        return seedWindowFor(randomActorHash(), countedDaysAgo);
    }

    private long seedWindowFor(String actorHash, Integer countedDaysAgo) {
        if (countedDaysAgo == null) {
            jdbc.update("INSERT INTO video_interaction_window"
                            + "(actor_type, actor_hash, vid, action, last_counted_at, created_at) "
                            + "VALUES ('USER', ?, ?, 'PLAY', NULL, TIMESTAMPADD(DAY, -40, NOW(3)))",
                    actorHash, vid);
        } else {
            jdbc.update("INSERT INTO video_interaction_window"
                            + "(actor_type, actor_hash, vid, action, last_counted_at, created_at) "
                            + "VALUES ('USER', ?, ?, 'PLAY', TIMESTAMPADD(DAY, ?, NOW(3)), "
                            + "TIMESTAMPADD(DAY, -40, NOW(3)))",
                    actorHash, vid, countedDaysAgo);
        }
        long id = jdbc.queryForObject(
                "SELECT id FROM video_interaction_window WHERE actor_hash = ? AND vid = ? "
                        + "AND action = 'PLAY'", Long.class, actorHash, vid);
        ownWindowIds.add(id);
        return id;
    }

    private void ageRequestToDaysAgo(String requestKey, int daysAgo) {
        jdbc.update("UPDATE video_interaction_request SET completed_at = TIMESTAMPADD(DAY, ?, "
                + "NOW(3)) WHERE request_key = ?", daysAgo, requestKey);
        Long id = jdbc.queryForObject(
                "SELECT id FROM video_interaction_request WHERE request_key = ?",
                Long.class, requestKey);
        if (id != null) {
            ownRequestIds.add(id);
        }
    }

    private VideoInteractionResult record(String actorHash, String requestKey) {
        return interactionService.record(new VideoInteractionCommand(
                VideoInteractionActorType.USER, actorHash, vid,
                VideoInteractionAction.PLAY, requestKey));
    }

    private boolean requestExists(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_interaction_request WHERE id = ?",
                Integer.class, id) == 1;
    }

    private boolean windowExists(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_interaction_window WHERE id = ?",
                Integer.class, id) == 1;
    }

    private int requestCount(String requestKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_interaction_request WHERE request_key = ?",
                Integer.class, requestKey);
    }

    private int windowCountFor(String actorHash) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_interaction_window WHERE actor_hash = ? AND vid = ?",
                Integer.class, actorHash, vid);
    }

    private String requestKeyOf(long requestId) {
        return jdbc.queryForObject(
                "SELECT request_key FROM video_interaction_request WHERE id = ?",
                String.class, requestId);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class, vid);
        assertNotNull(value);
        return value;
    }

    private long playTimes() {
        Long value = jdbc.queryForObject(
                "SELECT play_times FROM video_status WHERE vid = ?", Long.class, vid);
        assertNotNull(value);
        return value;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String randomActorHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
    }

    private static String randomRequestKey() {
        return "t15-cleanup-" + UUID.randomUUID();
    }
}
