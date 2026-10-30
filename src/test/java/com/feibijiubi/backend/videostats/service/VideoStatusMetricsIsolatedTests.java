package com.feibijiubi.backend.videostats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.videostats.config.VideoStatusMetricsConfig;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.controller.VideoStatusMetricsController;
import com.feibijiubi.backend.videostats.mapper.VideoStatusMapper;
import com.feibijiubi.backend.videostats.metrics.VideoStatusBacklogSampler;
import com.feibijiubi.backend.videostats.metrics.VideoStatusMetricsMapper;
import com.feibijiubi.backend.videostats.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusBatchFlushServiceImpl;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.videostats.vo.VideoStatusGateVO;
import com.feibijiubi.backend.videostats.vo.VideoStatusMetricsVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * T05 真实隔离验收（只新增本类，不改业务 / metrics 核心 / 已有 IT）。
 *
 * <p>与 {@code VideoStatusMetricsSamplerTests} 的 mock 单测互补：这里连真实 MySQL 13307，
 * 用真实 MyBatis mapper、真实 {@code VideoStatusTelemetry}、真实 {@code VideoStatusBacklogSampler}
 * 和真实 {@code VideoStatusBatchFlushServiceImpl}（Spring 事务代理）验收：</p>
 * <ol>
 *   <li><b>迁移幂等</b>：只执行 T05 的两条观测索引 DDL（同一 JDBC 会话，兼容 MySQL
 *       {@code PREPARE/EXECUTE}），连续执行两遍都成功且索引不重复、不丢数据；</li>
 *   <li><b>四类故障可查询</b>：随机新建 vid 并分别造 OUTBOX FAILED、OUTBOX PENDING(1)
 *       超过 120s、CONSUMED RECEIVED(0) 超过 180s、CONSUMED REPAIR_REQUIRED(3)，
 *       {@code sampleOnce()} 必须给出可定位（code + 有限 subject）的告警，
 *       {@code /api/admin/video-status/metrics} 的控制器读缓存路径也能查到；</li>
 *   <li><b>采集无状态变更</b>：采样前后该 vid 的 outbox / consumed / video_status 行逐字段相等；
 *       控制器只读缓存，不触发新采样、不碰数据库；</li>
 *   <li><b>封顶与全局最老</b>：mapper 的 {@code cap} 参数真实生效（cap=0 → 0，cap=1 → 1），
 *       全局计数只承诺 {@code >= 自身}，最老年龄是全库值且 {@code >= 自造样本年龄}；</li>
 *   <li><b>EXPLAIN 走新索引</b>：两条最老查询的执行计划 key 必须是
 *       {@code idx_video_status_outbox_created} / {@code idx_video_status_consumed_created}；</li>
 *   <li><b>端到端样本只在提交后上报</b>：真实 flush 提交时 spy 到的
 *       {@code recordFlushedPayload} 恰好一次；外层事务回滚时零次、样本数不变，
 *       且计数与事件状态真实回滚（不是 mock 的“假结果”）；</li>
 *   <li><b>门闩只读</b>：新格式 token 返回停留时间、老格式返回 {@code LEGACY_UNKNOWN}，
 *       只调用一次 {@code getString}，没有任何写操作。</li>
 * </ol>
 *
 * <p>隔离：复用 {@link VideoStatusCounterIsolatedTests.IsolationGuard}，只允许
 * {@code compose.test.yaml} 的 MySQL 13307 / Redis 16379；RabbitMQ 自动配置被排除，
 * 不连业务 MQ、不写 Redis（RedisUtils 为 mock），也不清理共享 dirty 集合。</p>
 *
 * <p>运行：{@code ./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusMetricsIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusMetricsIsolatedTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusCounterIsolatedTests.IsolationGuard.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VideoStatusMetricsIsolatedTests {

    private static final String MIGRATION_FILE =
            "database/migrations/t05-video-status-observability-indexes.sql";
    private static final String OUTBOX_TABLE = "video_status_outbox";
    private static final String CONSUMED_TABLE = "video_status_consumed_event";
    private static final String OUTBOX_INDEX = VideoStatusBacklogSampler.OUTBOX_CREATED_INDEX;
    private static final String CONSUMED_INDEX = VideoStatusBacklogSampler.CONSUMED_CREATED_INDEX;
    private static final int INDEX_COLUMNS = 3;
    private static final int SEEDED_AGE_SECONDS = 180;
    private static final long SEEDED_AGE_MILLIS = SEEDED_AGE_SECONDS * 1000L;
    private static final int BASELINE_PLAY_TIMES = 7;

    /** 有限标签集合：新增告警必须显式加进来，防止无界基数。 */
    private static final Set<String> ALARM_CODES = Set.of(
            "BACKLOG_CAPPED", "PENDING_TOO_OLD", "OUTBOX_FAILED",
            "REPAIR_REQUIRED", "SCHEDULER_STALLED");
    private static final Set<String> ALARM_SUBJECTS = Set.of(
            "OUTBOX/PENDING", "OUTBOX/SENDING", "OUTBOX/SENT", "OUTBOX/FAILED",
            "CONSUMED/RECEIVED", "CONSUMED/REDIS_APPLIED_PENDING_FLUSH",
            "CONSUMED/FLUSHED", "CONSUMED/REPAIR_REQUIRED",
            "RELAY_TICK", "FLUSH_TICK", "RECOVERY_TICK");

    /**
     * 只装配本类真正需要的 bean：
     * <ul>
     *   <li>{@code @MapperScan(basePackageClasses = ...)} 同时注册 metrics 只读 mapper
     *       与 flush 所需的业务 mapper；</li>
     *   <li>sampler / controller 用 {@code @Bean} 显式 {@code new}（父级退回的
     *       “无 @Autowired 构造”问题不在这里复现），依赖仍由容器注入；</li>
     *   <li>flush service 用 {@code @Import} 走真实容器 autowire + 真实事务代理；</li>
     *   <li>采样 executor 是 mock（{@code isShutdown()=true}），PostConstruct 不会注册后台任务。</li>
     * </ul>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
    @MapperScan(basePackageClasses = {VideoStatusMetricsMapper.class, VideoStatusMapper.class})
    @EnableConfigurationProperties(VideoStatusProperties.class)
    @Import(VideoStatusBatchFlushServiceImpl.class)
    static class TestApplication {

        /** 采样线程只存在于生产环境：测试里 mock 掉，避免后台执行干扰断言。 */
        @Bean(name = VideoStatusMetricsConfig.METRICS_SCHEDULER_BEAN, destroyMethod = "")
        ScheduledExecutorService videoStatusMetricsScheduler() {
            ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
            when(executor.isShutdown()).thenReturn(true);
            return executor;
        }

        /** 真实遥测 + spy：spy 委派真实实现，记录的是真实调用，不是伪造样本。 */
        @Bean
        VideoStatusTelemetry videoStatusTelemetry(ObjectMapper objectMapper) {
            return spy(new VideoStatusTelemetry(objectMapper));
        }

        @Bean
        VideoStatusBacklogSampler videoStatusBacklogSampler(
                VideoStatusMetricsMapper metricsMapper,
                VideoStatusTelemetry telemetry,
                VideoStatusProperties properties,
                @Qualifier(VideoStatusMetricsConfig.METRICS_SCHEDULER_BEAN)
                ScheduledExecutorService samplerExecutor) {
            return new VideoStatusBacklogSampler(metricsMapper, telemetry, properties, samplerExecutor);
        }

        /** 门闩观测只需要一次只读 GET：用 mock 保证“绝不写 Redis”。 */
        @Bean
        RedisUtils redisUtils() {
            return mock(RedisUtils.class);
        }

        @Bean
        VideoStatusMetricsController videoStatusMetricsController(
                VideoStatusTelemetry telemetry,
                VideoStatusBacklogSampler backlogSampler,
                RedisUtils redisUtils) {
            return new VideoStatusMetricsController(telemetry, backlogSampler, redisUtils);
        }
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    VideoStatusMetricsMapper metricsMapper;
    @Autowired
    VideoStatusBacklogSampler sampler;
    @Autowired
    VideoStatusMetricsController controller;
    @Autowired
    VideoStatusBatchFlushService flushService;
    @Autowired
    VideoStatusTelemetry telemetry;
    @Autowired
    RedisUtils redisUtils;
    @Autowired
    PlatformTransactionManager transactionManager;

    int vid;
    String outboxFailedEventId;
    String outboxPendingEventId;
    String consumedReceivedEventId;
    String consumedRepairEventId;
    String pendingFlushEventId;
    String pendingFlushPayload;

    // ------------------------------------------------------------- migration

    /**
     * 测试前只执行 T05 的两条观测索引 DDL，连续两遍证明幂等；不删任何数据、不删已有索引。
     *
     * <p>整段脚本必须在<b>同一个 JDBC 会话</b>执行，因为 MySQL 的
     * {@code SET @ddl / PREPARE / EXECUTE / DEALLOCATE} 使用会话变量与预处理语句。
     * 因此走 {@link JdbcTemplate#execute(ConnectionCallback)} 而不是逐条
     * {@code jdbc.update}（后者不保证连接复用）。</p>
     */
    @BeforeAll
    void ensureObservabilityIndexesIdempotently() throws Exception {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                            .startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"),
                    "the isolated MySQL endpoint must be the only target");
        }

        Path migration = Path.of(MIGRATION_FILE);
        assertTrue(Files.isRegularFile(migration), "missing migration file: " + migration.toAbsolutePath());
        List<String> statements = splitStatements(Files.readString(migration, StandardCharsets.UTF_8));
        String script = String.join("\n", statements);
        assertTrue(script.contains("CREATE INDEX " + OUTBOX_INDEX),
                "migration must create " + OUTBOX_INDEX);
        assertTrue(script.contains("CREATE INDEX " + CONSUMED_INDEX),
                "migration must create " + CONSUMED_INDEX);

        boolean outboxPreexisting = indexColumnCount(OUTBOX_TABLE, OUTBOX_INDEX) > 0;
        boolean consumedPreexisting = indexColumnCount(CONSUMED_TABLE, CONSUMED_INDEX) > 0;

        int firstPass = runMigrationPass(statements);
        assertEquals(INDEX_COLUMNS, indexColumnCount(OUTBOX_TABLE, OUTBOX_INDEX),
                "outbox observability index must exist after pass 1");
        assertEquals(INDEX_COLUMNS, indexColumnCount(CONSUMED_TABLE, CONSUMED_INDEX),
                "consumed observability index must exist after pass 1");

        int secondPass = runMigrationPass(statements);
        assertEquals(firstPass, secondPass,
                "second migration pass must be a no-op (no duplicate index / no data change)");
        assertEquals(INDEX_COLUMNS, indexColumnCount(OUTBOX_TABLE, OUTBOX_INDEX));
        assertEquals(INDEX_COLUMNS, indexColumnCount(CONSUMED_TABLE, CONSUMED_INDEX));

        assertTrue(metricsMapper.indexExists(OUTBOX_TABLE, OUTBOX_INDEX) > 0);
        assertTrue(metricsMapper.indexExists(CONSUMED_TABLE, CONSUMED_INDEX) > 0);

        System.out.printf(Locale.ROOT,
                "[T05-IT] migration idempotent: preexisting(outbox=%s,consumed=%s) pass1=%d pass2=%d%n",
                outboxPreexisting, consumedPreexisting, firstPass, secondPass);
    }

    private static List<String> splitStatements(String script) {
        StringBuilder withoutLineComments = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            withoutLineComments.append(line).append('\n');
        }
        return java.util.Arrays.stream(withoutLineComments.toString().split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    /** 同一连接内顺序执行：SET @ddl → PREPARE → EXECUTE → DEALLOCATE 才能共享会话状态。 */
    private int runMigrationPass(List<String> statements) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
            return null;
        });
        return indexColumnCount(OUTBOX_TABLE, OUTBOX_INDEX)
                + indexColumnCount(CONSUMED_TABLE, CONSUMED_INDEX);
    }

    private int indexColumnCount(String tableName, String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, tableName, indexName);
        return count == null ? 0 : count;
    }

    // ---------------------------------------------------------------- seeding

    /** 每个用例一个随机新 vid 和四条独立的故障样本；不删除任何历史行。 */
    @BeforeEach
    void seedOwnFaultRows() {
        clearInvocations(telemetry);
        reset(redisUtils);

        String username = "t05-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                        + "VALUES (?, 't05-metrics', 'test', 'test', '', '', '', '')",
                uid);
        vid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid, play_times) VALUES (?, ?)", vid, BASELINE_PLAY_TIMES);

        outboxFailedEventId = "t05-of-" + UUID.randomUUID();
        outboxPendingEventId = "t05-op-" + UUID.randomUUID();
        consumedReceivedEventId = "t05-cr-" + UUID.randomUUID();
        consumedRepairEventId = "t05-cp-" + UUID.randomUUID();
        pendingFlushEventId = "t05-cf-" + UUID.randomUUID();

        // 1) OUTBOX FAILED(3)：只影响计数告警
        insertOutbox(outboxFailedEventId, 3, 0);
        // 2) OUTBOX PENDING(1) 超过 120s：驱动 PENDING_TOO_OLD
        insertOutbox(outboxPendingEventId, 1, SEEDED_AGE_SECONDS);
        // 3) CONSUMED RECEIVED(0) consumed_at = DB NOW(3)-180s：驱动 PENDING_TOO_OLD
        insertConsumed(consumedReceivedEventId, 0, SEEDED_AGE_SECONDS, "{}");
        // 4) CONSUMED REPAIR_REQUIRED(3)：驱动 REPAIR_REQUIRED
        insertConsumed(consumedRepairEventId, 3, SEEDED_AGE_SECONDS, "{}");

        // flush 端到端样本：status 1 + 可解析 occurredAt 的真实 JSON payload
        pendingFlushPayload = "{\"eventId\":\"" + pendingFlushEventId
                + "\",\"eventType\":\"PLAY\",\"delta\":1,\"occurredAt\":"
                + (System.currentTimeMillis() - 1_500L) + "}";
        insertConsumed(pendingFlushEventId, 1, 0, pendingFlushPayload);
    }

    private void insertOutbox(String eventId, int status, int ageSeconds) {
        jdbc.update("INSERT INTO video_status_outbox("
                        + "event_id, aggregate_id, event_type, payload, status, retry_count, created_at) "
                        + "VALUES (?, ?, 'PLAY', '{}', ?, 0, TIMESTAMPADD(SECOND, -?, NOW(3)))",
                eventId, vid, status, ageSeconds);
    }

    private void insertConsumed(String eventId, int processStatus, int ageSeconds, String payload) {
        jdbc.update("INSERT INTO video_status_consumed_event("
                        + "event_id, vid, event_type, delta, payload, payload_hash, process_status, consumed_at) "
                        + "VALUES (?, ?, 'PLAY', 1, ?, ?, ?, TIMESTAMPADD(SECOND, -?, NOW(3)))",
                eventId, vid, payload, "0".repeat(64), processStatus, ageSeconds);
    }

    // --------------------------------------------------------------- 1) 四类故障

    @Test
    void sampleOnceLocatesFourFaultClassesWithoutChangingOwnVidState() {
        Map<String, List<Map<String, Object>>> before = ownVidState();

        VideoStatusBacklogSampler.Snapshot snapshot = sampler.sampleOnce();

        Map<String, List<Map<String, Object>>> after = ownVidState();
        assertEquals(before, after, "sampling must not change any row/status/counter of this vid");

        assertEquals(VideoStatusBacklogSampler.STATE_OK, snapshot.getState(),
                "with both indexes present the sample must be OK, not DEGRADED/STALE");
        assertFalse(snapshot.isStale());
        assertEquals(7, snapshot.getBacklog().size());

        assertBacklog(snapshot, "OUTBOX", 3, 1);
        assertBacklog(snapshot, "OUTBOX", 1, 1);
        assertBacklog(snapshot, "CONSUMED", 0, 1);
        assertBacklog(snapshot, "CONSUMED", 3, 1);

        assertTrue(reading(snapshot, "OUTBOX", 1).getOldestAgeMillis() >= SEEDED_AGE_MILLIS,
                "oldest is a global value and must be at least the seeded 180s age");
        assertTrue(reading(snapshot, "CONSUMED", 0).getOldestAgeMillis() >= SEEDED_AGE_MILLIS,
                "oldest is a global value and must be at least the seeded 180s age");
        assertNotNull(reading(snapshot, "OUTBOX", 3).getOldestAgeMillis());
        assertNotNull(reading(snapshot, "CONSUMED", 3).getOldestAgeMillis());

        assertAlarm(snapshot, "OUTBOX_FAILED", "OUTBOX/FAILED");
        assertAlarm(snapshot, "PENDING_TOO_OLD", "OUTBOX/SENDING");
        assertAlarm(snapshot, "PENDING_TOO_OLD", "CONSUMED/RECEIVED");
        assertAlarm(snapshot, "REPAIR_REQUIRED", "CONSUMED/REPAIR_REQUIRED");

        for (VideoStatusBacklogSampler.Alarm alarm : snapshot.getAlarms()) {
            assertTrue(ALARM_CODES.contains(alarm.getCode()), "unbounded alarm code: " + alarm.getCode());
            assertTrue(ALARM_SUBJECTS.contains(alarm.getSubject()),
                    "unbounded alarm subject: " + alarm.getSubject());
            assertFalse(alarm.getMessage().contains("t05-"),
                    "alarm message must not leak eventId/vid labels: " + alarm.getMessage());
        }

        // 可查询：控制器只读缓存快照，重复抓取不触发新采样、也不访问数据库。
        long sampledAt = snapshot.getSampledAtEpochMillis();
        VideoStatusMetricsVO first = controller.getMetrics().getData();
        VideoStatusMetricsVO second = controller.getMetrics().getData();
        assertEquals(sampledAt, first.getBacklog().getSampledAtEpochMillis(),
                "metrics query must return the cached sample, not re-sample");
        assertEquals(sampledAt, second.getBacklog().getSampledAtEpochMillis());
        assertEquals(VideoStatusBacklogSampler.STATE_OK, first.getBacklog().getState());
        assertEquals(7, first.getBacklog().getBacklog().size());
        assertEquals(VideoStatusTelemetry.Operation.values().length,
                first.getInstanceTelemetry().getOperations().size(),
                "instance telemetry must expose exactly the finite operation set");
        assertEquals(before, ownVidState(), "metrics query must not touch the DB");

        System.out.printf(Locale.ROOT,
                "[T05-IT] vid=%d alarms=%s outboxPendingOldest=%dms consumedReceivedOldest=%dms%n",
                vid, snapshot.getAlarms().size(),
                reading(snapshot, "OUTBOX", 1).getOldestAgeMillis(),
                reading(snapshot, "CONSUMED", 0).getOldestAgeMillis());
    }

    @Test
    void cappedMapperCountsIncludeOwnRowsWithoutPretendingToBeExactGlobals() {
        // cap 参数真实生效：cap=0 时即使有行也数 0，cap=1 时封顶 1。
        assertEquals(0, metricsMapper.countOutboxStatusCapped(1, 0));
        assertEquals(0, metricsMapper.countConsumedStatusCapped(0, 0));
        assertEquals(1, metricsMapper.countOutboxStatusCapped(3, 1));
        assertEquals(1, metricsMapper.countOutboxStatusCapped(1, 1));
        assertEquals(1, metricsMapper.countConsumedStatusCapped(0, 1));
        assertEquals(1, metricsMapper.countConsumedStatusCapped(3, 1));

        // 共享库里已有历史行：只能承诺“>= 自身”，不能断言精确 1。
        int globalOutboxFailed = metricsMapper.countOutboxStatusCapped(3, VideoStatusBacklogSampler.COUNT_CAP);
        int globalOutboxPending = metricsMapper.countOutboxStatusCapped(1, VideoStatusBacklogSampler.COUNT_CAP);
        int globalConsumedReceived = metricsMapper.countConsumedStatusCapped(
                0, VideoStatusBacklogSampler.COUNT_CAP);
        int globalConsumedRepair = metricsMapper.countConsumedStatusCapped(
                3, VideoStatusBacklogSampler.COUNT_CAP);
        assertTrue(globalOutboxFailed >= 1);
        assertTrue(globalOutboxPending >= 1);
        assertTrue(globalConsumedReceived >= 1);
        assertTrue(globalConsumedRepair >= 1);
        assertTrue(globalOutboxFailed <= VideoStatusBacklogSampler.COUNT_CAP);
        assertTrue(globalConsumedReceived <= VideoStatusBacklogSampler.COUNT_CAP);

        // 最老是全库值：只能 >= 自造样本年龄。
        assertTrue(metricsMapper.selectOldestOutboxAgeMillis(1) >= SEEDED_AGE_MILLIS);
        assertTrue(metricsMapper.selectOldestConsumedAgeMillis(0) >= SEEDED_AGE_MILLIS);

        System.out.printf(Locale.ROOT,
                "[T05-IT] capped counts (cap=%d): outboxFailed=%d outboxPending=%d "
                        + "consumedReceived=%d consumedRepair=%d%n",
                VideoStatusBacklogSampler.COUNT_CAP,
                globalOutboxFailed, globalOutboxPending, globalConsumedReceived, globalConsumedRepair);
    }

    // ---------------------------------------------------------- 2) EXPLAIN 索引

    @Test
    void oldestQueriesUseTheNewObservabilityIndexes() {
        List<Map<String, Object>> outboxPlan = jdbc.queryForList(
                "EXPLAIN SELECT CAST(TIMESTAMPDIFF(MICROSECOND, created_at, NOW(3)) / 1000 AS SIGNED) "
                        + "FROM video_status_outbox WHERE status = ? ORDER BY created_at, id LIMIT 1",
                1);
        assertEquals(OUTBOX_INDEX, outboxPlan.get(0).get("key"),
                "oldest outbox query must use the new index: " + outboxPlan);

        List<Map<String, Object>> consumedPlan = jdbc.queryForList(
                "EXPLAIN SELECT CAST(TIMESTAMPDIFF(MICROSECOND, consumed_at, NOW(3)) / 1000 AS SIGNED) "
                        + "FROM video_status_consumed_event WHERE process_status = ? "
                        + "ORDER BY consumed_at, id LIMIT 1",
                0);
        assertEquals(CONSUMED_INDEX, consumedPlan.get(0).get("key"),
                "oldest consumed query must use the new index: " + consumedPlan);

        System.out.printf(Locale.ROOT, "[T05-IT] explain keys: outbox=%s consumed=%s%n",
                outboxPlan.get(0).get("key"), consumedPlan.get(0).get("key"));
    }

    // ------------------------------------- 3) 真实 flush 提交 / 外层回滚 + 遥测

    @Test
    void realFlushCommitRecordsExactlyOneEndToEndSample() {
        long samplesBefore = telemetry.snapshot().getEndToEnd().getSamples();
        long unknownBefore = telemetry.snapshot().getEndToEnd().getUnknown();

        flushService.flushOneVideo(vid, 10);

        assertEquals(BASELINE_PLAY_TIMES + 1L, playTimes());
        assertEquals(2, processStatus(pendingFlushEventId), "event must be FLUSHED(2)");
        assertNotNull(flushedAt(pendingFlushEventId));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(telemetry, times(1)).recordFlushedPayload(payload.capture());
        assertTrue(payload.getValue().contains("occurredAt"), payload.getValue());

        VideoStatusTelemetry.EndToEndSnapshot endToEnd = telemetry.snapshot().getEndToEnd();
        assertEquals(samplesBefore + 1L, endToEnd.getSamples(),
                "a committed flush must add exactly one real end-to-end sample");
        assertEquals(unknownBefore, endToEnd.getUnknown(),
                "a parseable payload must not be counted as unknown");
        assertNotNull(endToEnd.getLastMillis());
        assertTrue(endToEnd.getLastMillis() >= 1_000L,
                "occurredAt was 1.5s ago, lastMillis=" + endToEnd.getLastMillis());

        System.out.printf(Locale.ROOT, "[T05-IT] commit: samples %d -> %d, last=%dms%n",
                samplesBefore, endToEnd.getSamples(), endToEnd.getLastMillis());
    }

    @Test
    void realFlushRolledBackByOuterTransactionAddsNoSample() {
        long samplesBefore = telemetry.snapshot().getEndToEnd().getSamples();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            flushService.flushOneVideo(vid, 10);
            // 同一事务内可读到已写入：说明下面的回滚证据不是“什么都没发生”。
            assertEquals(BASELINE_PLAY_TIMES + 1L, playTimes(),
                    "flush writes must be visible inside the outer transaction");
            assertEquals(2, processStatus(pendingFlushEventId));
            status.setRollbackOnly();
        });

        verify(telemetry, never()).recordFlushedPayload(anyString());
        assertEquals(samplesBefore, telemetry.snapshot().getEndToEnd().getSamples(),
                "rolled-back flush must not add an end-to-end sample");
        assertEquals(BASELINE_PLAY_TIMES, playTimes(), "counter must be rolled back");
        assertEquals(1, processStatus(pendingFlushEventId), "event must stay PENDING_FLUSH(1)");
        assertNull(flushedAt(pendingFlushEventId));
    }

    // ------------------------------------------------------------ 4) 门闩只读

    @Test
    void gateObservationReadsNewAndLegacyTokensWithoutWriting() {
        int gateVid = 424_242;
        String key = RedisKeyUtils.videoStatusRebuildGate(gateVid);

        when(redisUtils.getString(key)).thenReturn(null);
        VideoStatusGateVO absent = controller.getGate(gateVid).getData();
        assertFalse(absent.isPresent());
        assertEquals("ABSENT", absent.getTokenFormat());
        assertNull(absent.getTokenAgeMillis());

        when(redisUtils.getString(key)).thenReturn(UUID.randomUUID().toString());
        VideoStatusGateVO legacy = controller.getGate(gateVid).getData();
        assertTrue(legacy.isPresent());
        assertEquals("LEGACY_UNKNOWN", legacy.getTokenFormat());
        assertNull(legacy.getTokenAgeMillis(), "legacy token has no timestamp: never guess an age");

        long createdAt = System.currentTimeMillis() - 5_000L;
        when(redisUtils.getString(key)).thenReturn(createdAt + "-" + UUID.randomUUID());
        VideoStatusGateVO fresh = controller.getGate(gateVid).getData();
        assertEquals("EPOCH_MILLIS_TOKEN", fresh.getTokenFormat());
        assertNotNull(fresh.getTokenAgeMillis());
        assertTrue(fresh.getTokenAgeMillis() >= 5_000L);

        verify(redisUtils, times(3)).getString(key);
        verifyNoMoreInteractions(redisUtils);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, List<Map<String, Object>>> ownVidState() {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        state.put("outbox", jdbc.queryForList(
                "SELECT id, event_id, status, retry_count, next_retry_at, sending_at, lease_token, "
                        + "last_error, created_at, sent_at "
                        + "FROM video_status_outbox WHERE aggregate_id = ? ORDER BY id", vid));
        state.put("consumed", jdbc.queryForList(
                "SELECT id, event_id, process_status, delta, consumer_retry_count, last_attempt_at, "
                        + "last_error, consumed_at, redis_applied_at, flushed_at "
                        + "FROM video_status_consumed_event WHERE vid = ? ORDER BY id", vid));
        state.put("counters", jdbc.queryForList(
                "SELECT vid, play_times, like_times, unlike_times, comment_times, coin_times, "
                        + "share_times, collect_times, danmu_times "
                        + "FROM video_status WHERE vid = ?", vid));
        return state;
    }

    private VideoStatusBacklogSampler.BacklogReading reading(
            VideoStatusBacklogSampler.Snapshot snapshot, String source, int status) {
        return snapshot.getBacklog().stream()
                .filter(candidate -> candidate.getSource().equals(source)
                        && candidate.getStatus() == status)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing reading " + source + "/" + status));
    }

    private void assertBacklog(VideoStatusBacklogSampler.Snapshot snapshot,
                               String source,
                               int status,
                               long minCount) {
        VideoStatusBacklogSampler.BacklogReading reading = reading(snapshot, source, status);
        assertNotNull(reading.getCount(), source + "/" + status + " count must be known in OK state");
        assertTrue(reading.getCount() >= minCount,
                source + "/" + status + " count=" + reading.getCount() + " must be >= " + minCount);
    }

    private void assertAlarm(VideoStatusBacklogSampler.Snapshot snapshot,
                             String code,
                             String subject) {
        assertTrue(snapshot.getAlarms().stream()
                        .anyMatch(alarm -> code.equals(alarm.getCode())
                                && subject.equals(alarm.getSubject())),
                "missing alarm " + code + "@" + subject + ", actual="
                        + snapshot.getAlarms().stream()
                        .map(alarm -> alarm.getCode() + "@" + alarm.getSubject())
                        .toList());
    }

    private long playTimes() {
        Long value = jdbc.queryForObject(
                "SELECT play_times FROM video_status WHERE vid = ?", Long.class, vid);
        return value == null ? 0L : value;
    }

    private int processStatus(String eventId) {
        Integer status = jdbc.queryForObject(
                "SELECT process_status FROM video_status_consumed_event WHERE event_id = ?",
                Integer.class, eventId);
        return status == null ? -1 : status;
    }

    private String flushedAt(String eventId) {
        return jdbc.queryForObject(
                "SELECT flushed_at FROM video_status_consumed_event WHERE event_id = ?",
                String.class, eventId);
    }
}
