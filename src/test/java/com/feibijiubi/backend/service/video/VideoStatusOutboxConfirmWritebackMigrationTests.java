package com.feibijiubi.backend.service.video;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T07：confirm 回写独立预算的迁移 / mapper 静态契约（不连 DB、不启动 Spring）。
 *
 * <p>真实 MySQL 上的幂等执行由 {@link VideoStatusOutboxLeaseTests} 在
 * {@code -DvideoStatusIT=true} 时覆盖；本类保证旧库兼容脚本不会被改成破坏性 SQL。</p>
 */
class VideoStatusOutboxConfirmWritebackMigrationTests {

    private static final Path MIGRATION = Path.of(
            "database/migrations/t07-video-status-outbox-confirm-writeback.sql");
    private static final Path SCHEMA = Path.of("database/feibijiubi.sql");
    private static final Path MAPPER = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/VideoStatusOutboxMapper.xml");

    @Test
    void migrationAddsConfirmWritebackColumnIdempotentlyWithoutTouchingBusinessRows()
            throws IOException {
        assertTrue(Files.isRegularFile(MIGRATION),
                "missing migration: " + MIGRATION.toAbsolutePath());
        String executable = executableSql(Files.readString(MIGRATION, StandardCharsets.UTF_8));

        assertTrue(executable.contains("ADD COLUMN CONFIRM_WRITEBACK_COUNT"),
                "migration must add confirm_writeback_count");
        assertTrue(executable.contains("INT NOT NULL DEFAULT 0"),
                "old databases must get a safe 0 default for every existing row");
        assertTrue(executable.contains("INFORMATION_SCHEMA.COLUMNS"),
                "re-running on an old database must inspect metadata first");
        assertTrue(executable.contains("COUNT(*) = 0"),
                "ADD COLUMN must only run when the column is absent");
        assertTrue(executable.contains("'SELECT 1'"),
                "a second run must be a harmless no-op");

        for (String forbidden : List.of("DROP TABLE", "DELETE FROM", "TRUNCATE",
                "UPDATE VIDEO_STATUS_OUTBOX", "DROP COLUMN")) {
            assertFalse(executable.contains(forbidden),
                    "migration run must not contain " + forbidden
                            + " (rollback has to stay commented out)");
        }
    }

    @Test
    void freshSchemaMatchesMigrationAndKeepsAllThreeBudgets() throws IOException {
        String schema = Files.readString(SCHEMA, StandardCharsets.UTF_8);
        assertTrue(schema.contains("lease_recovery_count INT NOT NULL DEFAULT 0"),
                "fresh schema must keep the lease recovery budget column");
        assertTrue(schema.contains("confirm_writeback_count INT NOT NULL DEFAULT 0"),
                "fresh schema must include the confirm writeback budget column");
    }

    @Test
    void mapperIncrementsConfirmBudgetTerminallyAndNeverTouchesOtherBudgets()
            throws IOException {
        String xml = Files.readString(MAPPER, StandardCharsets.UTF_8);

        assertTrue(xml.contains("confirm_writeback_count = confirm_writeback_count + 1"),
                "markConfirmedPending / exhaustion must increment the dedicated budget");
        assertTrue(xml.contains("confirm writeback budget exhausted"),
                "exhaustion must be diagnosable by a fixed last_error reason");
        assertTrue(xml.contains("id=\"markConfirmWritebackExhausted\""),
                "relay needs an explicit terminal statement at the 10th failure");

        String confirmedPending = statement(
                xml, "id=\"markConfirmedPending\"", "</update>");
        assertFalse(confirmedPending.contains("retry_count"),
                "confirm writeback failure must not consume publish retry_count");
        assertFalse(confirmedPending.contains("lease_recovery_count"),
                "confirm writeback failure must not consume lease recovery budget");

        String exhausted = statement(
                xml, "id=\"markConfirmWritebackExhausted\"", "</update>");
        assertFalse(exhausted.contains("retry_count"),
                "terminal confirm writeback failure must not consume publish retry_count");
        assertFalse(exhausted.contains("lease_recovery_count"),
                "terminal confirm writeback failure must not consume lease recovery budget");
    }

    @Test
    void recoveryAndReplayKeepBudgetsIndependent() throws IOException {
        String mapper = Files.readString(MAPPER, StandardCharsets.UTF_8);
        String recover = statement(
                mapper, "id=\"recoverExpiredSending\"", "</update>");
        assertFalse(recover.contains("confirm_writeback_count"),
                "lease recovery must not consume the confirm writeback budget");
        assertFalse(recover.contains("retry_count = retry_count + 1"),
                "lease recovery must not consume the publish retry budget");

        String repairMapper = Files.readString(Path.of(
                        "src/main/resources/com/feibijiubi/backend/mapper/"
                                + "VideoStatusOutboxRepairMapper.xml"),
                StandardCharsets.UTF_8);
        String reset = statement(
                repairMapper, "id=\"resetFailedToPending\"", "</update>");
        for (String budget : List.of("retry_count = 0", "lease_recovery_count = 0",
                "confirm_writeback_count = 0")) {
            assertTrue(reset.contains(budget),
                    "audited manual replay must open a fresh budget for " + budget);
        }
    }

    /** 去掉整行 SQL 注释，避免把 rollback 段当成可执行语句。 */
    private String executableSql(String script) {
        StringBuilder sql = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                sql.append(line).append('\n');
            }
        }
        return sql.toString().toUpperCase(Locale.ROOT);
    }

    private String statement(String xml, String marker, String end) {
        int start = xml.indexOf(marker);
        assertTrue(start >= 0, "missing mapper statement " + marker);
        int endIndex = xml.indexOf(end, start);
        assertTrue(endIndex > start, "unterminated mapper statement " + marker);
        return xml.substring(start, endIndex);
    }
}
