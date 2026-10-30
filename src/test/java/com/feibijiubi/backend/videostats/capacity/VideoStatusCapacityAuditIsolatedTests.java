package com.feibijiubi.backend.videostats.capacity;

import com.feibijiubi.backend.infrastructure.redis.RedisConstants;
import com.feibijiubi.backend.infrastructure.redis.RedisKeyUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T14-A 只读容量核查 + 归档 dry-run 的真实隔离验收：MySQL 13307 + Redis 16379（{@code compose.test.yaml}）。
 *
 * <p>验收点：</p>
 * <ul>
 *   <li>只读：审计前后 MySQL 行快照、MySQL 写语句计数、Redis 写命令计数、DBSIZE、processed key
 *       的值/TTL、重建门闩的存在与“无 TTL”状态全部不变；</li>
 *   <li>禁删判定：只有终态（outbox SENT=2 / consumed FLUSHED=2）进入候选，status0/1/3 与
 *       FAILED 永不出现，且所有候选都标 {@code NOT_AUTHORIZED_FOR_DELETION}；</li>
 *   <li>有界：时间窗 + LIMIT（+1 探测截断），缺索引/访问路径不是索引时报告 {@code NOT_EVALUABLE}
 *       且不执行查询、不降级为全表扫描；SCAN 抽样以<b>命令次数</b>为硬上限（一次调用一次往返），
 *       COUNT 只是 hint，游标未归零就报覆盖截断，空匹配页不算完成；</li>
 *   <li>UNKNOWN：processed key 样本不足时单键字节与容量估算显式 UNKNOWN，绝不回落为 0。</li>
 * </ul>
 *
 * <p>本类不删除任何 MySQL 行、不改任何事件状态、不删 Redis key（只删除自己新建的 processed/gate 样本 key），
 * 也不连接正式端口。</p>
 *
 * <p>运行（先 {@code docker compose -f compose.test.yaml up -d --wait}）：</p>
 * <pre>
 * ./mvnw -DvideoStatusIT=true -Dtest=VideoStatusCapacityAuditIsolatedTests test
 * </pre>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusCapacityAuditIsolatedTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusCapacityAuditIsolatedTests.IsolationGuard.class)
class VideoStatusCapacityAuditIsolatedTests {

    /** 启用前拒绝一切非 {@code compose.test.yaml} 覆盖：连不上隔离端点就不该开始跑。 */
    static class IsolationGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var env = context.getEnvironment();
            Map<String, String> required = Map.of(
                    "spring.datasource.url", "jdbc:mysql://127.0.0.1:13307/feibijiubi"
                            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
                            + "&useSSL=false&allowPublicKeyRetrieval=true",
                    "spring.datasource.username", "root",
                    "spring.datasource.password", "isolated-test-only",
                    "spring.data.redis.host", "127.0.0.1",
                    "spring.data.redis.port", "16379");
            required.forEach((key, expected) -> {
                if (!expected.equals(env.getProperty(key))) {
                    throw new IllegalStateException("Refusing non-isolated test configuration: " + key);
                }
            });
            for (String key : List.of("spring.datasource.jndi-name", "spring.datasource.hikari.jdbc-url",
                    "spring.datasource.driver-class-name", "spring.data.redis.url",
                    "spring.data.redis.password", "spring.data.redis.cluster.nodes",
                    "spring.data.redis.sentinel.master")) {
                if (env.containsProperty(key)) {
                    throw new IllegalStateException("Unexpected connection override: " + key);
                }
            }
        }
    }

    /** 只装配 DataSource + Redis：没有 Rabbit 监听器、没有 Redisson、没有调度器，因此不会消费/落库/改状态。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
    static class TestApplication {
    }

    private static final String EVENT_ID_PREFIX = "t14a-capacity-";
    private static final int CONSUMED_TERMINAL = 2;
    private static final int OUTBOX_TERMINAL = 2;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;

    private final List<String> ownRedisKeys = new ArrayList<>();
    private int vid;

    @BeforeEach
    void seedBase() throws Exception {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                            .startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"),
                    "the isolated MySQL endpoint must be the only target");
        }
        vid = newVideo();
    }

    @AfterEach
    void deleteOwnRedisKeys() {
        if (!ownRedisKeys.isEmpty()) {
            redis.delete(ownRedisKeys);
            ownRedisKeys.clear();
        }
    }

    // ------------------------------------------------- 1) 只列终态 + 一律不授权删除

    @Test
    void dryRunListsOnlyTerminalRowsAndNeverAuthorizesDeletion() {
        String payloadMarker = "payload-marker-" + UUID.randomUUID();
        String errorMarker = "last-error-marker-" + UUID.randomUUID();

        List<Long> outboxTerminal = List.of(
                insertOutbox(OUTBOX_TERMINAL, 400, payloadMarker, errorMarker),
                insertOutbox(OUTBOX_TERMINAL, 400, payloadMarker, errorMarker));
        List<Long> outboxNonTerminal = List.of(
                insertOutbox(0, 400, payloadMarker, errorMarker),
                insertOutbox(1, 400, payloadMarker, errorMarker),
                insertOutbox(3, 400, payloadMarker, errorMarker));
        List<Long> consumedTerminal = List.of(
                insertConsumed(CONSUMED_TERMINAL, 400, payloadMarker, errorMarker),
                insertConsumed(CONSUMED_TERMINAL, 400, payloadMarker, errorMarker));
        List<Long> consumedNonTerminal = List.of(
                insertConsumed(0, 400, payloadMarker, errorMarker),
                insertConsumed(1, 400, payloadMarker, errorMarker),
                insertConsumed(3, 400, payloadMarker, errorMarker));

        VideoStatusCapacityReport report = audit(300, 1000).run();

        VideoStatusCapacityReport.CandidateWindow outboxWindow = report.windows().get(0);
        VideoStatusCapacityReport.CandidateWindow consumedWindow = report.windows().get(1);
        assertEquals(VideoStatusCapacityAudit.TABLE_OUTBOX, outboxWindow.table());
        assertEquals(VideoStatusCapacityReport.WINDOW_EVALUABLE, outboxWindow.status(),
                "isolated schema must provide the (status, created_at, id) index");
        assertEquals(VideoStatusCapacityReport.WINDOW_EVALUABLE, consumedWindow.status(),
                "isolated schema must provide the (process_status, consumed_at, id) index");
        assertFalse(outboxWindow.truncated(), "limit=1000 must not truncate a handful of fixture rows");

        List<Long> listedOutboxIds = ids(outboxWindow);
        assertTrue(listedOutboxIds.containsAll(outboxTerminal),
                "terminal (SENT=2) rows older than the window must be listed: " + listedOutboxIds);
        for (Long forbidden : outboxNonTerminal) {
            assertFalse(listedOutboxIds.contains(forbidden),
                    "PENDING/SENDING/FAILED must never be listed as deletable: id=" + forbidden);
        }
        List<Long> listedConsumedIds = ids(consumedWindow);
        assertTrue(listedConsumedIds.containsAll(consumedTerminal),
                "terminal (FLUSHED=2) rows older than the window must be listed: " + listedConsumedIds);
        for (Long forbidden : consumedNonTerminal) {
            assertFalse(listedConsumedIds.contains(forbidden),
                    "RECEIVED/REDIS_APPLIED/REPAIR_REQUIRED must never be listed: id=" + forbidden);
        }

        for (VideoStatusCapacityReport.CandidateWindow window : report.windows()) {
            for (VideoStatusCapacityReport.TerminalCandidate candidate : window.candidates()) {
                assertEquals(2, candidate.status(), "only terminal status=2 may appear");
                assertEquals(VideoStatusCapacityReport.DELETION_NOT_AUTHORIZED,
                        candidate.deletionAuthorization(),
                        "even terminal rows are not authorized for deletion without a retention decision");
            }
        }
        assertFalse(report.writePerformed());
        assertFalse(report.deletionAuthorized());

        String text = report.render();
        assertTrue(text.contains(VideoStatusCapacityReport.DELETION_NOT_AUTHORIZED));
        assertTrue(text.contains(VideoStatusCapacityReport.RETENTION_PERIOD_UNDECIDED));
        assertTrue(text.contains("不执行 COUNT(*)"), "row counts must be labelled as estimates");
        assertTrue(text.contains("禁止删除重建门闩"), "noeviction guidance must be present");
        for (String secret : List.of(payloadMarker, errorMarker, EVENT_ID_PREFIX,
                "127.0.0.1", "isolated-test-only", "jdbc:")) {
            assertFalse(text.contains(secret),
                    "report must not leak credentials/endpoints/payload/eventId: " + secret);
        }
    }

    // ------------------------------------------------------- 2) 只读：无任何写入副作用

    @Test
    void auditIsReadOnlyForMySqlRedisAndRebuildGate() {
        long outboxId = insertOutbox(OUTBOX_TERMINAL, 800, "payload-ro", "error-ro");
        long consumedId = insertConsumed(CONSUMED_TERMINAL, 800, "payload-ro", "error-ro");

        String processedKey = RedisKeyUtils.processedKey("t14a-" + UUID.randomUUID());
        String gateKey = RedisKeyUtils.videoStatusRebuildGate(vid);
        redis.opsForValue().set(processedKey, "1", Duration.ofSeconds(7200));
        ownRedisKeys.add(processedKey);
        redis.opsForValue().set(gateKey, "owner-token-t14a");
        ownRedisKeys.add(gateKey);

        Map<String, Object> outboxBefore = rowSnapshot(VideoStatusCapacityAudit.TABLE_OUTBOX, outboxId);
        Map<String, Object> consumedBefore = rowSnapshot(VideoStatusCapacityAudit.TABLE_CONSUMED, consumedId);
        Map<String, Long> mysqlWritesBefore = mysqlWriteCounters();
        Map<String, Long> redisWritesBefore = redisWriteCounters();
        long dbSizeBefore = redis.execute((RedisCallback<Long>) connection ->
                connection.serverCommands().dbSize());
        long processedTtlBefore = redis.getExpire(processedKey, TimeUnit.SECONDS);
        assertTrue(processedTtlBefore > 0, "fixture processed key must have a TTL");
        assertEquals(-1L, redis.getExpire(gateKey, TimeUnit.SECONDS), "fixture gate must have no TTL");

        VideoStatusCapacityAudit audit = audit(700, 1000);
        audit.run();
        audit.run();

        assertEquals(outboxBefore, rowSnapshot(VideoStatusCapacityAudit.TABLE_OUTBOX, outboxId),
                "outbox row must be byte-identical after the read-only audit");
        assertEquals(consumedBefore, rowSnapshot(VideoStatusCapacityAudit.TABLE_CONSUMED, consumedId),
                "consumed row must be byte-identical after the read-only audit");
        assertEquals(mysqlWritesBefore, mysqlWriteCounters(),
                "audit must not execute INSERT/UPDATE/DELETE/DDL");
        assertEquals(redisWritesBefore, redisWriteCounters(),
                "audit must not execute Redis write commands (SET/HSET/DEL/EXPIRE/CONFIG SET)");
        assertEquals(dbSizeBefore, redis.execute((RedisCallback<Long>) connection ->
                        connection.serverCommands().dbSize()),
                "audit must not create or delete Redis keys");
        assertEquals("owner-token-t14a", redis.opsForValue().get(gateKey));
        assertEquals(-1L, redis.getExpire(gateKey, TimeUnit.SECONDS),
                "audit must not add a TTL to the rebuild gate");
        assertEquals("1", redis.opsForValue().get(processedKey));
        long processedTtlAfter = redis.getExpire(processedKey, TimeUnit.SECONDS);
        assertTrue(processedTtlAfter > 0 && processedTtlAfter <= processedTtlBefore,
                "processed key TTL must only decay with wall clock, never be rewritten");
    }

    // ------------------------------------------------ 3) 缺索引/错误访问路径不可评估

    @Test
    void missingOrWrongIndexIsNotEvaluableAndNeverFallsBackToScan() {
        VideoStatusCapacityAudit audit = audit(30, 20);

        VideoStatusCapacityReport.CandidateWindow absentIndex = audit.evaluateWindow(
                new VideoStatusCapacityAudit.Window(VideoStatusCapacityAudit.TABLE_OUTBOX,
                        "status", "created_at", OUTBOX_TERMINAL,
                        "idx_video_status_outbox_absent"));
        assertEquals(VideoStatusCapacityReport.WINDOW_NOT_EVALUABLE, absentIndex.status());
        assertTrue(absentIndex.reason().contains("INDEX_MISSING"));
        assertTrue(absentIndex.candidates().isEmpty(), "missing index must not produce candidates");

        // 存在但列序不对（(status, next_retry_at, sending_at, id)）同样不可评估。
        VideoStatusCapacityReport.CandidateWindow wrongColumns = audit.evaluateWindow(
                new VideoStatusCapacityAudit.Window(VideoStatusCapacityAudit.TABLE_OUTBOX,
                        "status", "created_at", OUTBOX_TERMINAL,
                        "idx_video_status_outbox_poll"));
        assertEquals(VideoStatusCapacityReport.WINDOW_NOT_EVALUABLE, wrongColumns.status());
        assertTrue(wrongColumns.reason().contains("INDEX_MISSING"));
        assertTrue(wrongColumns.candidates().isEmpty());

        assertEquals(VideoStatusCapacityReport.WINDOW_EVALUABLE,
                audit.evaluateWindow(VideoStatusCapacityAudit.OUTBOX_WINDOW).status());
        assertEquals(VideoStatusCapacityReport.WINDOW_EVALUABLE,
                audit.evaluateWindow(VideoStatusCapacityAudit.CONSUMED_WINDOW).status());
    }

    @Test
    void onlyTerminalStatusTwoIsAcceptedAsCandidateWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> new VideoStatusCapacityAudit.Window(VideoStatusCapacityAudit.TABLE_OUTBOX,
                        "status", "created_at", 0, VideoStatusCapacityAudit.OUTBOX_WINDOW_INDEX),
                "status0 must never be a candidate window");
        assertThrows(IllegalArgumentException.class,
                () -> new VideoStatusCapacityAudit.Window(VideoStatusCapacityAudit.TABLE_CONSUMED,
                        "process_status", "consumed_at", 3, VideoStatusCapacityAudit.CONSUMED_WINDOW_INDEX),
                "status3 must never be a candidate window");
        assertThrows(IllegalArgumentException.class,
                () -> new VideoStatusCapacityAudit.Window("video_status_outbox; DROP TABLE video",
                        "status", "created_at", 2, VideoStatusCapacityAudit.OUTBOX_WINDOW_INDEX),
                "identifiers must stay whitelisted");
    }

    // ------------------------------------------------------ 4) 时间窗 + LIMIT 有界

    @Test
    void candidateWindowIsBoundedByTimeWindowAndLimit() {
        // 5 行终态历史 + 2 行“刚刚发生”的终态：时间窗必须把后者挡在候选之外。
        for (int i = 0; i < 5; i++) {
            insertOutbox(OUTBOX_TERMINAL, 1200, "p", "e");
        }
        List<Long> recentTerminal = List.of(
                insertOutbox(OUTBOX_TERMINAL, 0, "p", "e"),
                insertOutbox(OUTBOX_TERMINAL, 0, "p", "e"));

        VideoStatusCapacityReport report = audit(1100, 2).run();
        VideoStatusCapacityReport.CandidateWindow window = report.windows().get(0);

        assertEquals(VideoStatusCapacityReport.WINDOW_EVALUABLE, window.status());
        assertEquals(2, window.candidates().size(), "LIMIT must bound the dry-run window");
        assertTrue(window.truncated(), "more terminal rows than the limit must be reported as truncated");
        assertTrue(window.accessPath().contains(VideoStatusCapacityAudit.OUTBOX_WINDOW_INDEX),
                "the dry-run must use the (status, created_at, id) index: " + window.accessPath());
        assertFalse(window.accessPath().contains("type=ALL"),
                "a full scan must never be used for the dry-run: " + window.accessPath());
        for (Long recent : recentTerminal) {
            assertFalse(ids(window).contains(recent),
                    "rows newer than the time window must not be listed: id=" + recent);
        }
    }

    // ------------------------------------------------------------ 5) UNKNOWN 不是 0

    @Test
    void insufficientProcessedKeySampleIsUnknownAndNeverZero() {
        VideoStatusCapacityAudit.Options options = new VideoStatusCapacityAudit.Options(
                20, 30, 1_000L, 30, 200, 1_000, 50,
                RedisConstants.PROCESSED_PREFIX + "t14a-none-" + UUID.randomUUID() + "*");

        VideoStatusCapacityReport report = new VideoStatusCapacityAudit(jdbc, redis, options).run();

        VideoStatusCapacityReport.ProcessedKeySample sample = report.processedSample();
        assertEquals(VideoStatusCapacityReport.SAMPLE_STATUS_UNKNOWN, sample.status());
        assertEquals(0, sample.sampledKeys());
        assertNull(sample.minBytes());
        assertNull(sample.averageBytes());
        assertTrue(sample.note().contains("样本不足"));
        assertTrue(sample.budget().scanCommands() <= sample.budget().scanCommandLimit(),
                "SCAN 命令次数必须留在命令预算内");
        assertTrue(sample.note().contains("SCAN 命令 "), "command count must be reported honestly");
        assertEquals(sample.budget().scanCommands() < options.scanMaxIterations(), sample.scanComplete(),
                "只有没耗尽命令预算时游标才可能归零（空匹配不等于完成）");

        VideoStatusCapacityReport.Estimate redisDaily = report.estimates().get(1);
        assertTrue(redisDaily.range().isUnknown(), "no sample -> UNKNOWN, not 0 B/天");
        assertTrue(report.uncertainties().stream().anyMatch(note -> note.contains("processed key 单键字节")));

        String text = report.render();
        assertTrue(text.contains("单键字节: UNKNOWN（"));
        assertFalse(text.contains(": 0 B/天"), "UNKNOWN must never render as 0 B/天");
    }

    @Test
    void scanCommandBudgetIsEnforcedAgainstRealRedisAndMatchesCommandStats() {
        // 唯一能证明“命令次数有界”的证据：真实 Redis 的 cmdstat_scan 调用增量。
        VideoStatusCapacityAudit.Options options = new VideoStatusCapacityAudit.Options(
                20, 30, 1_000L, 30, 3, 1, 50,
                RedisConstants.PROCESSED_PREFIX + "t14a-empty-" + UUID.randomUUID() + "*");
        long commandsBefore = scanCommandCalls();

        VideoStatusCapacityReport report = new VideoStatusCapacityAudit(jdbc, redis, options).run();
        long commandsAfter = scanCommandCalls();

        VideoStatusCapacityReport.ProcessedKeySample sample = report.processedSample();
        assertEquals(3, sample.budget().scanCommands(),
                "命令预算 = 3，且每页都匹配为空：必须正好发 3 条 SCAN（不追到游标归零）");
        assertEquals(3, commandsAfter - commandsBefore,
                "真实 Redis 的 cmdstat_scan 增量必须等于记录的 SCAN 命令数: delta="
                        + (commandsAfter - commandsBefore));
        assertFalse(sample.scanComplete(), "空匹配页 + 命令预算耗尽：不得声称遍历完成");
        assertEquals(0, sample.returnedKeys());
        assertEquals(0, sample.sampledKeys());
        assertEquals(VideoStatusCapacityReport.SAMPLE_STATUS_UNKNOWN, sample.status());
        assertTrue(sample.note().contains("游标归零=否（覆盖截断）"), sample.note());
        assertTrue(report.render().contains("游标归零=否（覆盖截断"), "报告必须标注覆盖截断");
        System.out.println("T14-A SCAN command budget: cmdstat_scan delta=" + (commandsAfter - commandsBefore)
                + " recordedScanCommands=" + sample.budget().scanCommands()
                + " returnedKeys=" + sample.returnedKeys()
                + " sampledKeys=" + sample.sampledKeys()
                + " scanComplete=" + sample.scanComplete());
    }

    @Test
    void boundedScanSamplesProcessedKeyBytesWithMemoryUsage() {
        String prefix = RedisConstants.PROCESSED_PREFIX + "t14a-sample-" + UUID.randomUUID();
        for (int i = 0; i < 8; i++) {
            String key = prefix + "-" + i;
            redis.opsForValue().set(key, "1");
            ownRedisKeys.add(key);
        }
        VideoStatusCapacityAudit.Options options = new VideoStatusCapacityAudit.Options(
                20, 30, 1_000L, 30, 200, 1_000, 50, prefix + "*");

        VideoStatusCapacityReport report = new VideoStatusCapacityAudit(jdbc, redis, options).run();
        VideoStatusCapacityReport.ProcessedKeySample sample = report.processedSample();

        assertEquals(VideoStatusCapacityReport.SAMPLE_STATUS_SAMPLED, sample.status(),
                "MEMORY USAGE must return a byte size for every sampled processed key");
        assertEquals(8, sample.sampledKeys());
        assertTrue(sample.minBytes() > 0, "measured key bytes must be positive");
        assertTrue(sample.maxBytes() >= sample.minBytes());
        assertTrue(sample.averageBytes() >= sample.minBytes() && sample.averageBytes() <= sample.maxBytes());
        assertTrue(sample.budget().scanCommands() >= 1);
        assertTrue(sample.budget().scanCommands() <= options.scanMaxIterations());
        assertEquals(8, sample.returnedKeys(), "SCAN 只返回这 8 个匹配 key");
        assertTrue(sample.sampledKeys() <= sample.budget().sampleKeyLimit());
        assertTrue(report.render().contains("单键字节: min="));
    }

    // ------------------------------------------------------- 6) 生成隔离样本报告文件

    @Test
    void writesReadOnlyDryRunReportToTempPath() throws IOException {
        insertOutbox(OUTBOX_TERMINAL, 4000, "payload-report", "error-report");
        insertConsumed(CONSUMED_TERMINAL, 4000, "payload-report", "error-report");

        VideoStatusCapacityReport report =
                new VideoStatusCapacityAudit(jdbc, redis, VideoStatusCapacityAudit.Options.fromSystemProperties())
                        .run();
        Path path = writeReport(report);

        assertTrue(Files.isRegularFile(path), "dry-run report must be written to disk: " + path);
        String written = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(written.contains("T14-A"));
        assertTrue(written.contains(VideoStatusCapacityReport.MODE_READ_ONLY_DRY_RUN));
        assertTrue(written.contains("writePerformed=false"));
        assertTrue(written.contains(VideoStatusCapacityReport.DELETION_NOT_AUTHORIZED));
        assertTrue(written.contains(VideoStatusCapacityReport.RETENTION_PERIOD_UNDECIDED));
        assertTrue(written.contains("公式:"));
        assertTrue(written.contains("不执行 COUNT(*)"));
        assertTrue(written.contains("禁止删除重建门闩"));
        assertTrue(written.contains("未统计 processed key 总数"));
        for (String secret : List.of("payload-report", "error-report", "127.0.0.1",
                "isolated-test-only", "jdbc:", "root")) {
            assertFalse(written.contains(secret), "report must not leak: " + secret);
        }
        assertFalse(report.writePerformed());
        assertFalse(report.deletionAuthorized());
        System.out.println("T14-A capacity dry-run report written to " + path.toAbsolutePath());
        System.out.println("T14-A processed sample: " + report.processedSample());
    }

    // ------------------------------------------------------------------- helpers

    private VideoStatusCapacityAudit audit(int windowDays, int candidateLimit) {
        return new VideoStatusCapacityAudit(jdbc, redis, new VideoStatusCapacityAudit.Options(
                candidateLimit, windowDays, 1_000L, 30, 20, 200, 100,
                RedisConstants.PROCESSED_PREFIX + "*"));
    }

    private static List<Long> ids(VideoStatusCapacityReport.CandidateWindow window) {
        return window.candidates().stream().map(VideoStatusCapacityReport.TerminalCandidate::id).toList();
    }

    private long insertOutbox(int status, int ageDays, String payloadMarker, String lastError) {
        String eventId = EVENT_ID_PREFIX + UUID.randomUUID();
        jdbc.update("INSERT INTO video_status_outbox("
                        + "event_id, aggregate_id, event_type, payload, status, last_error, created_at) "
                        + "VALUES (?, ?, 'PLAY', ?, ?, ?, DATE_SUB(NOW(3), INTERVAL ? DAY))",
                eventId, vid, "{\"marker\":\"" + payloadMarker + "\"}", status, lastError, ageDays);
        return jdbc.queryForObject(
                "SELECT id FROM video_status_outbox WHERE event_id = ?", Long.class, eventId);
    }

    private long insertConsumed(int processStatus, int ageDays, String payloadMarker, String lastError) {
        String eventId = EVENT_ID_PREFIX + UUID.randomUUID();
        jdbc.update("INSERT INTO video_status_consumed_event("
                        + "event_id, vid, event_type, delta, payload, payload_hash, process_status, "
                        + "last_error, consumed_at) "
                        + "VALUES (?, ?, 'PLAY', 1, ?, ?, ?, ?, DATE_SUB(NOW(3), INTERVAL ? DAY))",
                eventId, vid, "{\"marker\":\"" + payloadMarker + "\"}", "0".repeat(64),
                processStatus, lastError, ageDays);
        return jdbc.queryForObject(
                "SELECT id FROM video_status_consumed_event WHERE event_id = ?", Long.class, eventId);
    }

    private Map<String, Object> rowSnapshot(String table, long id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM " + table + " WHERE id = ?", id);
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        assertNotNull(normalized.get("id"));
        return normalized;
    }

    /** MySQL 写语句计数：审计前后必须完全一致，用来证明“只读”不是口头承诺。 */
    private Map<String, Long> mysqlWriteCounters() {
        List<String> watched = List.of("Com_insert", "Com_update", "Com_delete", "Com_replace",
                "Com_truncate", "Com_alter_table", "Com_create_table", "Com_drop_table");
        Map<String, Long> counters = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SHOW GLOBAL STATUS")) {
            String name = String.valueOf(column(row, "Variable_name"));
            if (watched.contains(name)) {
                counters.put(name, Long.parseLong(String.valueOf(column(row, "Value"))));
            }
        }
        assertEquals(8, counters.size(), "expected all MySQL write counters to be readable");
        return counters;
    }

    /** Redis 写命令计数：SET/HSET/SADD/ZADD/DEL/EXPIRE/CONFIG SET 的 calls 必须不变。 */
    private Map<String, Long> redisWriteCounters() {
        Properties stats = redis.execute((RedisCallback<Properties>) connection ->
                connection.serverCommands().info("commandstats"));
        assertNotNull(stats);
        Map<String, Long> counters = new LinkedHashMap<>();
        for (String command : List.of("cmdstat_set", "cmdstat_setex", "cmdstat_hset", "cmdstat_sadd",
                "cmdstat_zadd", "cmdstat_del", "cmdstat_unlink", "cmdstat_expire", "cmdstat_persist",
                "cmdstat_config|set", "cmdstat_flushall", "cmdstat_flushdb")) {
            counters.put(command, calls(stats.getProperty(command)));
        }
        return counters;
    }

    /** 真实 Redis 的 SCAN 调用数（{@code INFO commandstats} 的 {@code cmdstat_scan}）：命令次数的唯一直接证据。 */
    private long scanCommandCalls() {
        Properties stats = redis.execute((RedisCallback<Properties>) connection ->
                connection.serverCommands().info("commandstats"));
        assertNotNull(stats);
        return calls(stats.getProperty("cmdstat_scan"));
    }

    private static Object column(Map<String, Object> row, String name) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static long calls(String commandStat) {
        if (commandStat == null) {
            return 0L;
        }
        for (String part : commandStat.split(",")) {
            if (part.startsWith("calls=")) {
                return Long.parseLong(part.substring("calls=".length()).trim());
            }
        }
        return 0L;
    }

    private Path writeReport(VideoStatusCapacityReport report) throws IOException {
        String configured = System.getProperty("videoStatusCapacityReportPath");
        Path path = configured == null || configured.isBlank()
                ? Files.createTempFile("video-status-capacity-audit-", ".txt")
                : Path.of(configured);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, report.render(), StandardCharsets.UTF_8);
        return path;
    }

    private int newVideo() {
        int uid = newUser();
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                        + "VALUES (?, 't14a-capacity', 'test', 'test', '', '', '', '')",
                uid);
        return jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
    }

    private int newUser() {
        String username = "t14a-capacity-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')",
                username);
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
    }
}
