package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusOutbox;
import com.feibijiubi.backend.mapper.VideoStatusOutboxMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T07 真实隔离验收：Outbox 租约恢复预算与确认超时/非法消息的 SQL 语义。
 *
 * <p>只连 {@code compose.test.yaml} 的 MySQL 127.0.0.1:13307（复用
 * {@link VideoStatusCounterIsolatedTests.IsolationGuard} 与 {@link TestApplication} 的门控思路），
 * 不启动 RabbitMQ 自动配置、不发布任何 MQ 消息、不跑老 IT、不启停容器；每个用例只新建自己的
 * video + outbox 行，不删除历史行。</p>
 *
 * <p>验收点：</p>
 * <ol>
 *   <li><b>迁移幂等</b>：{@code database/migrations/t07-video-status-outbox-lease-recovery.sql}
 *       连续执行 3 遍都成功，只多出 {@code lease_recovery_count INT NOT NULL DEFAULT 0}，
 *       行数不变、没有 DROP/DELETE；</li>
 *   <li><b>租约恢复独立预算</b>：{@code recoverExpiredSending} 只涨 {@code lease_recovery_count}，
 *       {@code retry_count} 不变；未耗尽时回 PENDING 且 {@code next_retry_at ≈ NOW(3)+1s}；</li>
 *   <li><b>9 次可再成功、第 10 次终结</b>：第 9 次恢复后仍可被新 owner 领取并 markSent 成 SENT；
 *       第 10 次过期直接 FAILED，原因固定为 {@code lease recovery budget exhausted}；</li>
 *   <li><b>CAS 接管</b>：恢复后 lease_token 被清空，旧 owner 的 markSent/markPending/markFailed/
 *       markConfirmedPending/markInvalidFailed 全部 0 行，新 owner 用新 token 才能接管；</li>
 *   <li><b>确认超时/非法消息不挤发布重试</b>：{@code markConfirmedPending} 回 PENDING、
 *       {@code markInvalidFailed} 置 FAILED，两者都不增加 {@code retry_count}；</li>
 *   <li><b>启动期跨字段校验</b>：默认 60/5 通过；租约 &lt; confirm + 5s 被拒；
 *       roundBudget 小于 confirm 仍合法（单轮预算不负责强杀进行中的 publish）。</li>
 * </ol>
 *
 * <p>运行：{@code ./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusOutboxLeaseTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusOutboxLeaseTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusCounterIsolatedTests.IsolationGuard.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VideoStatusOutboxLeaseTests {

    private static final String MIGRATION_FILE =
            "database/migrations/t07-video-status-outbox-lease-recovery.sql";
    private static final String CONFIRM_WRITEBACK_MIGRATION_FILE =
            "database/migrations/t07-video-status-outbox-confirm-writeback.sql";
    private static final String TABLE = "video_status_outbox";
    private static final String COLUMN = "lease_recovery_count";
    private static final String CONFIRM_WRITEBACK_COLUMN = "confirm_writeback_count";
    /** 与 XML 中的硬编码预算一致：第 10 次租约过期直接 FAILED。 */
    private static final int LEASE_RECOVERY_FAILURE_BUDGET = 10;
    private static final String EXHAUSTED_REASON = "lease recovery budget exhausted";
    /** 与 XML 中的硬编码预算一致：第 10 次 confirm 后回写失败直接 FAILED。 */
    private static final int CONFIRM_WRITEBACK_FAILURE_BUDGET = 10;
    private static final String CONFIRM_WRITEBACK_EXHAUSTED_REASON =
            "confirm writeback budget exhausted";
    private static final int MIGRATION_PASSES = 3;

    /**
     * DataSource + JdbcTemplate + MyBatis mapper：没有 Redis / RabbitMQ / 调度器。
     * 与 {@code VideoStatusSqlBaselineTests.TestApplication} 同一形态，只换成本类需要扫描的 mapper。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
    @MapperScan("com.feibijiubi.backend.mapper")
    static class TestApplication {
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    VideoStatusOutboxMapper mapper;

    int vid;

    // ------------------------------------------------------------- migration

    /**
     * 在跑任何 mapper 之前，把 T07 增量迁移连续执行 3 遍：证明幂等、只加列、不动数据。
     *
     * <p>与 T05 迁移测试一致，整段脚本必须在同一个 JDBC 会话执行（{@code SET @ddl → PREPARE →
     * EXECUTE → DEALLOCATE} 依赖会话变量），所以走 {@link JdbcTemplate#execute(ConnectionCallback)}
     * 而不是逐条 {@code jdbc.update}。</p>
     */
    @BeforeAll
    void ensureLeaseRecoveryColumnIdempotently() throws Exception {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                            .startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"),
                    "the isolated MySQL endpoint must be the only target");
        }

        Path migration = Path.of(MIGRATION_FILE);
        assertTrue(Files.isRegularFile(migration),
                "missing migration file: " + migration.toAbsolutePath());
        List<String> statements = splitStatements(Files.readString(migration, StandardCharsets.UTF_8));

        Path confirmMigration = Path.of(CONFIRM_WRITEBACK_MIGRATION_FILE);
        assertTrue(Files.isRegularFile(confirmMigration),
                "missing migration file: " + confirmMigration.toAbsolutePath());
        List<String> confirmStatements = splitStatements(
                Files.readString(confirmMigration, StandardCharsets.UTF_8));

        String executable = String.join(";", statements).toUpperCase(Locale.ROOT);
        assertTrue(executable.contains("ADD COLUMN LEASE_RECOVERY_COUNT"),
                "migration must add " + COLUMN);
        String confirmExecutable = String.join(";", confirmStatements).toUpperCase(Locale.ROOT);
        assertTrue(confirmExecutable.contains("ADD COLUMN CONFIRM_WRITEBACK_COUNT"),
                "migration must add " + CONFIRM_WRITEBACK_COLUMN);
        for (String sql : List.of(executable, confirmExecutable)) {
            assertFalse(sql.contains("DROP COLUMN"),
                    "rollback must stay commented out; a migration run must never drop the column");
            assertFalse(sql.contains("DROP TABLE"), "migration must never drop tables");
            assertFalse(sql.contains("DELETE FROM"),
                    "migration must never delete idempotency rows");
            assertFalse(sql.contains("TRUNCATE"), "migration must never truncate outbox");
        }

        long rowsBefore = outboxRowCount();
        boolean preexisting = leaseRecoveryColumnCount() > 0;
        boolean confirmPreexisting = columnCount(CONFIRM_WRITEBACK_COLUMN) > 0;

        for (int pass = 1; pass <= MIGRATION_PASSES; pass++) {
            runMigrationPass(statements);
            runMigrationPass(confirmStatements);
            assertEquals(1, leaseRecoveryColumnCount(),
                    "pass " + pass + " must leave exactly one " + COLUMN + " column");
            assertEquals(1, columnCount(CONFIRM_WRITEBACK_COLUMN),
                    "pass " + pass + " must leave exactly one "
                            + CONFIRM_WRITEBACK_COLUMN + " column");
            assertEquals(rowsBefore, outboxRowCount(),
                    "migration must not change the outbox row count (pass " + pass + ")");
        }

        assertEquals("int", columnDetail("LOWER(DATA_TYPE)"));
        assertEquals("NO", columnDetail("IS_NULLABLE"));
        assertEquals("0", columnDetail("COLUMN_DEFAULT"));
        assertEquals("int", columnDetail(CONFIRM_WRITEBACK_COLUMN, "LOWER(DATA_TYPE)"));
        assertEquals("NO", columnDetail(CONFIRM_WRITEBACK_COLUMN, "IS_NULLABLE"));
        assertEquals("0", columnDetail(CONFIRM_WRITEBACK_COLUMN, "COLUMN_DEFAULT"));

        System.out.printf(Locale.ROOT,
                "[T07-IT] migration idempotent: preexisting=%s confirmPreexisting=%s "
                        + "passes=%d outboxRows=%d%n",
                preexisting, confirmPreexisting, MIGRATION_PASSES, rowsBefore);
    }

    /** 去掉整行注释后按分号切分；注释掉的 rollback 不会被执行。 */
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

    private void runMigrationPass(List<String> statements) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
            return null;
        });
    }

    private int leaseRecoveryColumnCount() {
        return columnCount(COLUMN);
    }

    private int columnCount(String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class, TABLE, column);
        return count == null ? 0 : count;
    }

    private long outboxRowCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class);
        return count == null ? 0L : count;
    }

    /** expression 只能是本类传入的常量表达式，不拼接外部输入。 */
    private String columnDetail(String expression) {
        return columnDetail(COLUMN, expression);
    }

    private String columnDetail(String column, String expression) {
        return jdbc.queryForObject(
                "SELECT " + expression + " FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, TABLE, column);
    }

    // ---------------------------------------------------------------- seeding

    @BeforeEach
    void seedOwnVideo() {
        String username = "t07-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 't07-lease', 'test', 'test', '', '', '', '')", uid);
        vid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
    }

    private static String newEventId() {
        return "t07-" + UUID.randomUUID();
    }

    private static String newToken(String label) {
        return "t07-" + label + "-" + UUID.randomUUID();
    }

    /** 直接造一条 SENDING 行：retry_count 代表“历史发布失败次数”，用于证明租约恢复不动它。 */
    private long insertSendingRow(String eventId,
                                  int retryCount,
                                  String leaseToken,
                                  LocalDateTime sendingAt) {
        jdbc.update("INSERT INTO video_status_outbox("
                        + "event_id, aggregate_id, event_type, payload, status, retry_count, "
                        + "sending_at, lease_token, last_error, created_at) "
                        + "VALUES (?, ?, 'PLAY', '{}', 1, ?, ?, ?, 'seeded', NOW(3))",
                eventId, vid, retryCount, sendingAt, leaseToken);
        return idOf(eventId);
    }

    /** 走真实 mapper insert，证明 DB 默认值 lease_recovery_count=0 对新事件成立。 */
    private long insertPendingRow(String eventId) {
        VideoStatusOutbox outbox = new VideoStatusOutbox();
        outbox.setEventId(eventId);
        outbox.setAggregateId(vid);
        outbox.setEventType("PLAY");
        outbox.setPayload("{}");
        assertEquals(1, mapper.insert(outbox), "mapper insert must create one PENDING row");
        assertNotNull(outbox.getId(), "generated key must come back");
        return outbox.getId();
    }

    private long idOf(String eventId) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM " + TABLE + " WHERE event_id = ?", Long.class, eventId);
        assertNotNull(id, "seeded row must exist: " + eventId);
        return id;
    }

    private OutboxRow row(String eventId) {
        return jdbc.queryForObject(
                "SELECT id, status, retry_count, lease_recovery_count, confirm_writeback_count, "
                        + "next_retry_at, sending_at, "
                        + "lease_token, last_error, sent_at FROM " + TABLE + " WHERE event_id = ?",
                (rs, index) -> new OutboxRow(
                        rs.getLong("id"),
                        rs.getInt("status"),
                        rs.getInt("retry_count"),
                        rs.getInt("lease_recovery_count"),
                        rs.getInt("confirm_writeback_count"),
                        rs.getObject("next_retry_at", LocalDateTime.class),
                        rs.getObject("sending_at", LocalDateTime.class),
                        rs.getString("lease_token"),
                        rs.getString("last_error"),
                        rs.getObject("sent_at", LocalDateTime.class)),
                eventId);
    }

    private LocalDateTime nowMinusHours(long hours) {
        return LocalDateTime.now().minusHours(hours);
    }

    private LocalDateTime cutoff() {
        return LocalDateTime.now().minusMinutes(30);
    }

    /**
     * 恢复语句用数据库时钟 {@code NOW(3)}，不能拿 JVM 本地时间直接比：
     * 隔离库里 {@code serverTimezone=UTC}，本地 JVM 是 +08:00。用同一个数据库时钟算差值，
     * 既验证了 NOW(3)+1s 的语义，也不依赖两边时区配置一致。
     */
    private void assertNextRetryAboutOneSecond(String eventId) {
        Long secondsFromDbNow = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, NOW(3), next_retry_at) FROM " + TABLE
                        + " WHERE event_id = ?",
                Long.class, eventId);
        assertNotNull(secondsFromDbNow,
                "recovered PENDING row must schedule the next attempt with NOW(3)+1s");
        assertTrue(secondsFromDbNow >= 0 && secondsFromDbNow <= 2,
                "next_retry_at must be NOW(3)+1s, secondsFromDbNow=" + secondsFromDbNow);
    }

    /** 恢复语句是全局 SQL：若共享库里已有别的过期 SENDING 行，本类的精确断言会显式失败而不是悄悄放过。 */
    private void assertRecoveredExactlyOne(int recoveredRows) {
        assertEquals(1, recoveredRows,
                "expected exactly this test's expired SENDING row; check for foreign "
                        + "status=1 rows with old sending_at in the shared isolated database");
    }

    // ------------------------------------------------- 1) 租约恢复独立预算

    @Test
    void leaseRecoveryUsesItsOwnBudgetAndRequeuesOneSecondLater() {
        String eventId = newEventId();
        long id = insertSendingRow(eventId, 3, newToken("owner"), nowMinusHours(1));

        assertRecoveredExactlyOne(mapper.recoverExpiredSending(cutoff()));

        OutboxRow first = row(eventId);
        assertEquals(0, first.status(), "recovered row must be PENDING");
        assertEquals(3, first.retryCount(),
                "lease recovery must not consume the publish retry budget");
        assertEquals(1, first.leaseRecoveryCount());
        assertNull(first.leaseToken(), "lease token must be cleared so the old owner cannot write");
        assertNull(first.sendingAt());
        assertNextRetryAboutOneSecond(eventId);
        assertTrue(first.lastError().contains("租约超时"), first.lastError());

        // 同一行再过期一次：仍只涨 lease_recovery_count
        assertEquals(1, mapper.markSending(id, newToken("owner2"), nowMinusHours(1)),
                "PENDING row must be re-claimable by a new lease");
        assertRecoveredExactlyOne(mapper.recoverExpiredSending(cutoff()));

        OutboxRow second = row(eventId);
        assertEquals(0, second.status());
        assertEquals(3, second.retryCount());
        assertEquals(2, second.leaseRecoveryCount());
        assertNextRetryAboutOneSecond(eventId);

        // 实体映射：新列必须能被 selectPendingForUpdate 真实读回（不是只写不读）
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE
                        + " WHERE status = 0 AND (next_retry_at IS NULL OR next_retry_at <= ?)",
                Integer.class, LocalDateTime.now().plusSeconds(30));
        assertNotNull(pending);
        List<VideoStatusOutbox> mapped = mapper.selectPendingForUpdate(
                pending + 1, LocalDateTime.now().plusSeconds(30));
        VideoStatusOutbox mine = mapped.stream()
                .filter(candidate -> eventId.equals(candidate.getEventId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("recovered row missing from selectPendingForUpdate"));
        assertEquals(2, mine.getLeaseRecoveryCount(),
                "lease_recovery_count must map onto the entity");
        assertEquals(3, mine.getRetryCount());
    }

    // --------------------------------------- 2) 9 次可再成功 / 第 10 次终结

    @Test
    void nineLeaseRecoveriesKeepEventPendingAndItStillSends() {
        String eventId = newEventId();
        long id = insertSendingRow(eventId, 2, newToken("nine"), nowMinusHours(2));

        for (int attempt = 1; attempt <= 9; attempt++) {
            if (attempt > 1) {
                assertEquals(1,
                        mapper.markSending(id, newToken("nine" + attempt), nowMinusHours(1)),
                        "attempt " + attempt + ": recovered row must be re-claimable");
            }
            assertRecoveredExactlyOne(mapper.recoverExpiredSending(cutoff()));

            OutboxRow current = row(eventId);
            assertEquals(0, current.status(),
                    "after " + attempt + " recoveries the event must still be PENDING");
            assertEquals(attempt, current.leaseRecoveryCount(),
                    "each recovery must count exactly once");
            assertEquals(2, current.retryCount(),
                    "lease recoveries must never touch publish retry_count");
            assertNextRetryAboutOneSecond(eventId);
        }

        // 第 9 次恢复后仍在预算内：新 owner 接管并成功发布到 SENT
        String freshToken = newToken("nine-fresh");
        assertEquals(1, mapper.markSending(id, freshToken, LocalDateTime.now()),
                "a row recovered 9 times must still be claimable");
        assertEquals(1, mapper.markSent(eventId, freshToken, LocalDateTime.now()));

        OutboxRow sent = row(eventId);
        assertEquals(2, sent.status(), "9 recoveries must not prevent a successful publish");
        assertNotNull(sent.sentAt());
        assertEquals(2, sent.retryCount());
        assertEquals(9, sent.leaseRecoveryCount());
        assertNull(sent.leaseToken());
    }

    @Test
    void tenthLeaseExpiryFailsWithLeaseRecoveryBudgetExhausted() {
        String eventId = newEventId();
        long id = insertSendingRow(eventId, 2, newToken("ten"), nowMinusHours(1));
        jdbc.update("UPDATE " + TABLE + " SET lease_recovery_count = ? WHERE id = ?",
                LEASE_RECOVERY_FAILURE_BUDGET - 1, id);

        assertRecoveredExactlyOne(mapper.recoverExpiredSending(cutoff()));

        OutboxRow failed = row(eventId);
        assertEquals(3, failed.status(),
                "the " + LEASE_RECOVERY_FAILURE_BUDGET + "th lease expiry must fail the event");
        assertEquals(EXHAUSTED_REASON, failed.lastError());
        assertEquals(LEASE_RECOVERY_FAILURE_BUDGET, failed.leaseRecoveryCount());
        assertEquals(2, failed.retryCount(),
                "terminal lease recovery must not consume publish retry budget either");
        assertNull(failed.leaseToken());
        assertNull(failed.sendingAt());
        assertNull(failed.nextRetryAt(), "FAILED row must not keep a retry schedule");

        // 幂等：已经 FAILED 的行不会再被恢复计数
        assertEquals(0, mapper.recoverExpiredSending(cutoff()));
        OutboxRow unchanged = row(eventId);
        assertEquals(3, unchanged.status());
        assertEquals(LEASE_RECOVERY_FAILURE_BUDGET, unchanged.leaseRecoveryCount());
        assertEquals(EXHAUSTED_REASON, unchanged.lastError());
    }

    // ------------------------------------------------------- 3) CAS 接管

    @Test
    void recoveredLeaseCannotBeWrittenByOldOwnerAndNewOwnerTakesOver() {
        String eventId = newEventId();
        long id = insertPendingRow(eventId);
        String oldToken = newToken("old");

        assertEquals(1, mapper.markSending(id, oldToken, nowMinusHours(1)),
                "PENDING -> SENDING CAS must take the lease");
        assertRecoveredExactlyOne(mapper.recoverExpiredSending(cutoff()));

        OutboxRow recovered = row(eventId);
        assertEquals(0, recovered.status());
        assertNull(recovered.leaseToken(), "recovery must clear the lease token");
        assertEquals(1, recovered.leaseRecoveryCount());
        assertEquals(0, recovered.retryCount(), "mapper insert + recovery must leave retry_count at 0");

        LocalDateTime now = LocalDateTime.now();
        assertEquals(0, mapper.markSent(eventId, oldToken, now),
                "old owner must not mark SENT after the lease was recovered");
        assertEquals(0, mapper.markPending(eventId, oldToken, now, "old owner"),
                "old owner must not mark PENDING after the lease was recovered");
        assertEquals(0, mapper.markFailed(eventId, oldToken, "old owner"),
                "old owner must not mark FAILED after the lease was recovered");
        assertEquals(0, mapper.markConfirmedPending(eventId, oldToken, now, "old owner"),
                "old owner must not mark confirmed-PENDING after the lease was recovered");
        assertEquals(0, mapper.markInvalidFailed(eventId, oldToken, "old owner"),
                "old owner must not mark invalid-FAILED after the lease was recovered");

        OutboxRow stillPending = row(eventId);
        assertEquals(0, stillPending.status(), "stale CAS writes must not change the row");
        assertEquals(0, stillPending.retryCount(), "stale CAS writes must not consume publish retries");
        assertEquals(1, stillPending.leaseRecoveryCount());
        assertTrue(stillPending.lastError().contains("租约超时"), stillPending.lastError());

        String newToken = newToken("new");
        assertEquals(1, mapper.markSending(id, newToken, now),
                "new owner must be able to claim the recovered PENDING row");
        assertEquals(1, mapper.markSent(eventId, newToken, now));

        OutboxRow sent = row(eventId);
        assertEquals(2, sent.status());
        assertEquals(1, sent.leaseRecoveryCount());
        assertNull(sent.leaseToken());
        assertNotNull(sent.sentAt());
    }

    // --------------------------------- 4) 确认超时 / 非法消息不挤发布重试

    @Test
    void confirmedPendingReturnsToPendingWithoutConsumingPublishRetry() {
        String eventId = newEventId();
        String token = newToken("confirm");
        insertSendingRow(eventId, 4, token, LocalDateTime.now());
        LocalDateTime expectedNextRetryAt =
                LocalDateTime.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);

        assertEquals(1, mapper.markConfirmedPending(
                        eventId, token, expectedNextRetryAt, "publish confirm timeout"),
                "current lease owner must be able to requeue a timed-out confirm");

        OutboxRow pending = row(eventId);
        assertEquals(0, pending.status());
        assertEquals(4, pending.retryCount(),
                "confirm timeout is 'unknown', it must not consume publish retries");
        assertEquals(expectedNextRetryAt, pending.nextRetryAt());
        assertNull(pending.leaseToken());
        assertNull(pending.sendingAt());
        assertEquals("publish confirm timeout", pending.lastError());
        assertEquals(0, pending.leaseRecoveryCount(),
                "confirm timeout is not a lease recovery either");
        assertEquals(1, pending.confirmWritebackCount(),
                "confirm 后 markSent 回写失败只消耗独立 confirm_writeback 预算");

        // 旧 token 已失效：重复调用/错误 token 都是 0 行，且不再改任何计数
        assertEquals(0, mapper.markConfirmedPending(
                eventId, token, expectedNextRetryAt, "duplicate"));
        assertEquals(0, mapper.markConfirmedPending(
                eventId, newToken("other"), expectedNextRetryAt, "wrong owner"));
        OutboxRow unchanged = row(eventId);
        assertEquals(4, unchanged.retryCount());
        assertEquals(1, unchanged.confirmWritebackCount());
        assertEquals("publish confirm timeout", unchanged.lastError());
    }

    @Test
    void tenthConfirmedWritebackFailureFailsWithItsOwnExhaustedReason() {
        String eventId = newEventId();
        String token = newToken("confirm-budget");
        long id = insertSendingRow(eventId, 4, token, LocalDateTime.now());
        jdbc.update("UPDATE " + TABLE + " SET confirm_writeback_count = ? WHERE id = ?",
                CONFIRM_WRITEBACK_FAILURE_BUDGET - 1, id);

        assertEquals(1, mapper.markConfirmedPending(
                        eventId, token, LocalDateTime.now().plusSeconds(1), "CONFIRM_WRITEBACK_FAILED"),
                "the 10th writeback failure must be recorded exactly once");

        OutboxRow failed = row(eventId);
        assertEquals(3, failed.status(),
                "the 10th confirm-writeback failure must form a terminal state, not requeue every second");
        assertEquals(CONFIRM_WRITEBACK_EXHAUSTED_REASON, failed.lastError());
        assertEquals(CONFIRM_WRITEBACK_FAILURE_BUDGET, failed.confirmWritebackCount());
        assertEquals(4, failed.retryCount(),
                "confirm-writeback budget must not consume publish retries");
        assertEquals(0, failed.leaseRecoveryCount(),
                "confirm-writeback budget must not consume lease recovery budget");
        assertNull(failed.nextRetryAt());
        assertNull(failed.leaseToken());

        // 幂等：FAILED 后再调用不会继续改写，也不会重复加计数。
        assertEquals(0, mapper.markConfirmedPending(
                eventId, token, LocalDateTime.now().plusSeconds(1), "again"));
        OutboxRow unchanged = row(eventId);
        assertEquals(3, unchanged.status());
        assertEquals(CONFIRM_WRITEBACK_FAILURE_BUDGET, unchanged.confirmWritebackCount());
        assertEquals(CONFIRM_WRITEBACK_EXHAUSTED_REASON, unchanged.lastError());
    }

    @Test
    void invalidPayloadFailsWithoutConsumingPublishRetry() {
        String eventId = newEventId();
        String token = newToken("invalid");
        insertSendingRow(eventId, 4, token, LocalDateTime.now());

        assertEquals(1, mapper.markInvalidFailed(
                        eventId, token, "invalid payload: not JSON"),
                "current lease owner must be able to fail an undeliverable event");

        OutboxRow failed = row(eventId);
        assertEquals(3, failed.status());
        assertEquals(4, failed.retryCount(),
                "an invalid payload is a permanent rejection, not a publish retry");
        assertNull(failed.leaseToken());
        assertNull(failed.sendingAt());
        assertEquals("invalid payload: not JSON", failed.lastError());
        assertEquals(0, failed.leaseRecoveryCount());

        assertEquals(0, mapper.markInvalidFailed(eventId, token, "again"));
        OutboxRow unchanged = row(eventId);
        assertEquals(3, unchanged.status());
        assertEquals(4, unchanged.retryCount());
        assertEquals("invalid payload: not JSON", unchanged.lastError());
    }

    // --------------------------------------------- 5) 启动期跨字段校验

    @Test
    void defaultLeaseLeavesFiveSecondMarginAfterPublishConfirm() {
        VideoStatusProperties defaults = new VideoStatusProperties();
        assertEquals(60, defaults.getOutboxLeaseSeconds());
        assertEquals(5, defaults.getPublishConfirmTimeoutSeconds());
        assertTrue(defaults.getOutboxLeaseSeconds()
                        >= defaults.getPublishConfirmTimeoutSeconds()
                        + VideoStatusProperties.MIN_LEASE_CONFIRM_MARGIN_SECONDS,
                "defaults must satisfy the T07 cross-field rule");
        assertDoesNotThrow(defaults::validate);
    }

    @Test
    void leaseShorterThanConfirmPlusMarginIsRejectedAtStartup() {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setPublishConfirmTimeoutSeconds(10);

        properties.setOutboxLeaseSeconds(14);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                properties::validate,
                "lease 14s < confirm 10s + 5s must be rejected");
        assertTrue(error.getMessage().contains("outbox-lease-seconds"), error.getMessage());
        assertTrue(error.getMessage().contains("15"), error.getMessage());

        properties.setOutboxLeaseSeconds(15);
        assertDoesNotThrow(properties::validate,
                "lease == confirm + 5s is the exact boundary and must pass");
    }

    @Test
    void roundBudgetSmallerThanConfirmTimeoutIsStillAccepted() {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setRoundBudgetMs(1);
        properties.setPublishConfirmTimeoutSeconds(30);
        properties.setOutboxLeaseSeconds(35);
        assertDoesNotThrow(properties::validate,
                "round budget only bounds 'claim the next item'; it cannot kill an in-flight publish");
    }

    /** 一行 outbox 的只读快照。 */
    private record OutboxRow(
            long id,
            int status,
            int retryCount,
            int leaseRecoveryCount,
            int confirmWritebackCount,
            LocalDateTime nextRetryAt,
            LocalDateTime sendingAt,
            String leaseToken,
            String lastError,
            LocalDateTime sentAt) {
    }
}
