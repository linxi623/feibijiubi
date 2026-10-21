package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T01 SQL baseline for the video-statistics counters.
 *
 * <p>Explicit opt-in ({@code -DvideoStatusIT=true}). It only opens the isolated
 * {@code compose.test.yaml} MySQL (127.0.0.1:13307) through the existing
 * {@code classpath:video-status-isolated.properties}, never {@code application.yml}. Redis and RabbitMQ are
 * not started here; MyBatis is started only so the real {@link VideoStatusMapper#selectByVid} mapping can be
 * measured against {@code BIGINT UNSIGNED} values.
 *
 * <p>This class records <em>current</em> behaviour. It does not assert limits that only T02 is supposed to
 * implement (for example rejecting values above {@code Long.MAX_VALUE}); those observations are printed and
 * documented as "current baseline", not as a passing requirement.
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusSqlBaselineTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusSqlBaselineTests.IsolationGuard.class)
class VideoStatusSqlBaselineTests {

    /**
     * Same guard idea as {@code VideoStatusIsolatedIntegrationTests.IsolationGuard}, and at least as strict:
     * the datasource must be exactly the isolated test one, and every non-test override is rejected.
     */
    static class IsolationGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var env = context.getEnvironment();
            Map<String, String> required = Map.of(
                    "spring.datasource.url", "jdbc:mysql://127.0.0.1:13307/feibijiubi?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                    "spring.datasource.username", "root",
                    "spring.datasource.password", "isolated-test-only");
            required.forEach((key, expected) -> {
                if (!expected.equals(env.getProperty(key))) {
                    throw new IllegalStateException("Refusing non-isolated test configuration: " + key);
                }
            });
            // Any hikari / jndi / driver override is an endpoint we do not control -> refuse, whatever its name.
            // Both the raw name and its relaxed (env-var style) form are checked so SPRING_DATASOURCE_HIKARI_*
            // cannot slip through.
            for (var propertySource : env.getPropertySources()) {
                if (!(propertySource instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String name : enumerable.getPropertyNames()) {
                    if (isForbiddenDatasourceKey(name)) {
                        throw new IllegalStateException("Unexpected datasource override: " + name);
                    }
                }
            }
            for (String key : List.of("spring.datasource.driver-class-name", "spring.datasource.type",
                    "spring.datasource.jndi-name", "spring.datasource.hikari.jdbc-url")) {
                if (env.containsProperty(key)) throw new IllegalStateException("Unexpected datasource override: " + key);
            }
        }

        private static boolean isForbiddenDatasourceKey(String name) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            String relaxed = lower.replace('_', '.');
            for (String candidate : List.of(lower, relaxed)) {
                for (String prefix : List.of("spring.datasource.hikari.", "spring.datasource.jndi",
                        "spring.datasource.url.", "spring.datasource.xa.", "spring.datasource.driver-class-name",
                        "spring.datasource.type")) {
                    if (candidate.startsWith(prefix)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** DataSource + JdbcTemplate + MyBatis mappers only: no Redis, no RabbitMQ, no scheduled tasks. */
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
    VideoStatusMapper videoStatusMapper;
    int vid;

    @BeforeEach
    void seed() {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"),
                    "the isolated MySQL endpoint must be the only target");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        String username = "sql-baseline-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 'sql-baseline', 'test', 'test', '', '', '', '')", uid);
        vid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid) VALUES (?)", vid);
    }

    /** Same shape as {@code VideoStatusMapper.applyBatchDelta} for the single PLAY column. */
    private static final String GUARDED_DELTA = "UPDATE video_status SET play_times = play_times + ? "
            + "WHERE vid = ? AND play_times + ? >= 0";

    private void setPlayTimes(long value) {
        jdbc.update("UPDATE video_status SET play_times = ? WHERE vid = ?", value, vid);
    }

    /** Reads through {@code BigDecimal}: the only lossless way to observe the UNSIGNED 64-bit column. */
    private BigDecimal playTimes() {
        return jdbc.queryForObject("SELECT play_times FROM video_status WHERE vid = ?", BigDecimal.class, vid);
    }

    private static String firstLine(Throwable e) {
        return String.valueOf(e.getMessage()).split("\n")[0];
    }

    private String attempt(String label, String sql, Object... args) {
        String outcome;
        try {
            outcome = "rows=" + jdbc.update(sql, args);
        } catch (RuntimeException e) {
            outcome = "error=" + e.getClass().getSimpleName() + ": " + firstLine(e);
        }
        System.out.printf("[SQL-BASELINE] %s -> %s (play_times=%s)%n", label, outcome, playTimes().toPlainString());
        return outcome;
    }

    /** Runs one statement on the caller's connection so the session sql_mode is shared. */
    private String attemptOn(Connection connection, String label, String sql, Object... args) {
        String outcome;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            outcome = "rows=" + statement.executeUpdate();
        } catch (SQLException e) {
            outcome = "error=" + e.getClass().getSimpleName() + ": " + firstLine(e);
        }
        System.out.printf("[SQL-BASELINE] %s -> %s (play_times=%s)%n",
                label, outcome, playTimesOn(connection).toPlainString());
        return outcome;
    }

    private BigDecimal playTimesOn(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT play_times FROM video_status WHERE vid = ?")) {
            statement.setInt(1, vid);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setPlayTimesOn(Connection connection, long value) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE video_status SET play_times = ? WHERE vid = ?")) {
            statement.setLong(1, value);
            statement.setInt(2, vid);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String sessionModeOn(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT @@SESSION.sql_mode");
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void setSessionModeOn(Connection connection, String mode) {
        // Only [A-Za-z0-9_,] is legal in sql_mode, so this interpolation cannot inject anything.
        String sanitized = mode.replaceAll("[^A-Za-z0-9_,]", "");
        try (PreparedStatement statement = connection.prepareStatement(
                "SET SESSION sql_mode = '" + sanitized + "'")) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void recordsServerVersionAndSqlMode() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        String sessionMode = jdbc.queryForObject("SELECT @@SESSION.sql_mode", String.class);
        String globalMode = jdbc.queryForObject("SELECT @@GLOBAL.sql_mode", String.class);
        String strict = jdbc.queryForObject("SELECT @@SESSION.innodb_strict_mode", String.class);
        String columnType = jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'video_status' "
                + "AND COLUMN_NAME = 'play_times'", String.class);
        System.out.printf("[SQL-BASELINE] version=%s session_sql_mode=%s global_sql_mode=%s "
                        + "innodb_strict_mode=%s play_times_type=%s%n",
                version, sessionMode, globalMode, strict, columnType);
        assertNotNull(version);
        assertTrue(version.startsWith("8."), "compose.test.yaml pins mysql:8.4, got " + version);
        assertEquals("bigint unsigned", columnType.toLowerCase());
        assertNotNull(sessionMode);
    }

    @Test
    void zeroMinusOneNeverWrapsUnsignedColumn() {
        setPlayTimes(0);
        String outcome = attempt("0 + (-1) raw, default sql_mode",
                "UPDATE video_status SET play_times = play_times + ? WHERE vid = ?", -1L, vid);
        // Either MySQL rejects the underflow or it leaves the value at 0; a wrap to 18446744073709551615 is a defect.
        assertEquals(0, BigDecimal.ZERO.compareTo(playTimes()),
                "UNSIGNED underflow must not mutate or wrap: " + outcome);
    }

    @Test
    void oneMinusOneBecomesZero() {
        setPlayTimes(1);
        String outcome = attempt("1 + (-1) raw", "UPDATE video_status SET play_times = play_times + ? WHERE vid = ?",
                -1L, vid);
        assertEquals(0, BigDecimal.ZERO.compareTo(playTimes()), "1 + (-1) must become 0: " + outcome);
        assertEquals("rows=1", outcome);
    }

    @Test
    void guardedBatchDeltaAtZeroIsRejectedWithoutMutation() {
        setPlayTimes(0);
        String outcome = attempt("guarded 0 + (-1), default sql_mode", GUARDED_DELTA, -1L, vid, -1L);
        // Baseline evidence for T02: the guard expression itself may raise 1690 instead of filtering the row.
        assertTrue(outcome.equals("rows=0") || outcome.startsWith("error="),
                "guarded underflow must either match no row or fail explicitly, got " + outcome);
        assertEquals(0, BigDecimal.ZERO.compareTo(playTimes()));
    }

    @Test
    void guardedBatchDeltaOneMinusOneSucceeds() {
        setPlayTimes(1);
        String outcome = attempt("guarded 1 + (-1)", GUARDED_DELTA, -1L, vid, -1L);
        assertEquals("rows=1", outcome);
        assertEquals(0, BigDecimal.ZERO.compareTo(playTimes()));
    }

    @Test
    void netZeroBatchKeepsValueAndMatchesRow() {
        setPlayTimes(5);
        String outcome = attempt("guarded 5 + 0", GUARDED_DELTA, 0L, vid, 0L);
        assertEquals("rows=1", outcome);
        assertEquals(0, new BigDecimal(5).compareTo(playTimes()));
    }

    /**
     * Contrast between the default unsigned arithmetic and {@code NO_UNSIGNED_SUBTRACTION}, measured on one
     * session (one connection) against an isolated row, restoring the original session mode in {@code finally}.
     *
     * <p>Both the {@code col + (-1)} form used by {@code applyBatchDelta} and the {@code col - 1} form used by
     * {@code decreaseLikeTimes}/{@code decreaseCollectTimes} are measured, because
     * {@code NO_UNSIGNED_SUBTRACTION} only affects the {@code -} operator.
     */
    @Test
    void sessionSqlModeWithoutUnsignedSubtractionChangesGuardOutcome() {
        String subtractDefault = "UPDATE video_status SET play_times = play_times - ? WHERE vid = ?";
        String guardedSubtract = "UPDATE video_status SET play_times = play_times - ? WHERE vid = ? AND play_times - ? >= 0";
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            String originalMode = sessionModeOn(connection);
            String defaultOutcome;
            String signedOutcome;
            String subtractDefaultOutcome;
            String subtractSignedOutcome;
            String guardedSubtractSignedOutcome;
            try {
                setSessionModeOn(connection, originalMode);
                setPlayTimesOn(connection, 0);
                defaultOutcome = attemptOn(connection, "guarded 0 + (-1), session=default", GUARDED_DELTA, -1L, vid, -1L);
                subtractDefaultOutcome = attemptOn(connection, "0 - 1 (no guard), session=default",
                        subtractDefault, 1L, vid);

                setSessionModeOn(connection, originalMode + ",NO_UNSIGNED_SUBTRACTION");
                System.out.printf("[SQL-BASELINE] session_sql_mode=NO_UNSIGNED_SUBTRACTION -> %s%n",
                        sessionModeOn(connection));
                setPlayTimesOn(connection, 0);
                signedOutcome = attemptOn(connection, "guarded 0 + (-1), session=NO_UNSIGNED_SUBTRACTION",
                        GUARDED_DELTA, -1L, vid, -1L);
                assertEquals(0, BigDecimal.ZERO.compareTo(playTimesOn(connection)),
                        "the guard must not mutate the row in either mode");
                subtractSignedOutcome = attemptOn(connection, "0 - 1 (no guard), session=NO_UNSIGNED_SUBTRACTION",
                        subtractDefault, 1L, vid);
                assertEquals(0, BigDecimal.ZERO.compareTo(playTimesOn(connection)),
                        "0 - 1 must not write a negative or wrapped value into the UNSIGNED column");
                guardedSubtractSignedOutcome = attemptOn(connection, "guarded 0 - 1, session=NO_UNSIGNED_SUBTRACTION",
                        guardedSubtract, 1L, vid, 1L);
                assertEquals(0, BigDecimal.ZERO.compareTo(playTimesOn(connection)));

                setPlayTimesOn(connection, 1);
                assertEquals("rows=1", attemptOn(connection, "guarded 1 + (-1), session=NO_UNSIGNED_SUBTRACTION",
                        GUARDED_DELTA, -1L, vid, -1L));
                assertEquals(0, BigDecimal.ZERO.compareTo(playTimesOn(connection)));
            } finally {
                setSessionModeOn(connection, originalMode);
            }
            assertEquals(originalMode, sessionModeOn(connection), "session sql_mode must be restored");
            System.out.printf("[SQL-BASELINE] guard outcome default=%s vs NO_UNSIGNED_SUBTRACTION=%s%n",
                    defaultOutcome, signedOutcome);
            System.out.printf("[SQL-BASELINE] subtraction outcome default=%s vs NO_UNSIGNED_SUBTRACTION=%s "
                            + "(guarded subtraction under NO_UNSIGNED_SUBTRACTION=%s)%n",
                    subtractDefaultOutcome, subtractSignedOutcome, guardedSubtractSignedOutcome);
            // Whatever the mode does, the statements must never corrupt the row (checked above).
            assertTrue(defaultOutcome.equals("rows=0") || defaultOutcome.startsWith("error="));
            assertTrue(signedOutcome.equals("rows=0") || signedOutcome.startsWith("error="));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Current baseline for 3e9: MySQL stores it; Java must not silently read a different number. */
    @Test
    void threeBillionRoundTripsThroughBigDecimalAndRealMapper() {
        setPlayTimes(3_000_000_000L);
        assertEquals(0, new BigDecimal("3000000000").compareTo(playTimes()));

        String outcome = attempt("guarded 3e9 + 1", GUARDED_DELTA, 1L, vid, 1L);
        assertEquals("rows=1", outcome);
        assertEquals(0, new BigDecimal("3000000001").compareTo(playTimes()));

        String jdbcInt;
        try {
            jdbcInt = "value=" + jdbc.queryForObject("SELECT play_times FROM video_status WHERE vid = ?",
                    Integer.class, vid);
        } catch (RuntimeException e) {
            jdbcInt = "error=" + e.getClass().getSimpleName() + ": " + firstLine(e);
        }
        System.out.printf("[SQL-BASELINE] jdbc Integer read of 3e9 -> %s%n", jdbcInt);

        String mapper;
        VideoStatus mapped = null;
        try {
            mapped = videoStatusMapper.selectByVid(vid);
            mapper = "value=" + mapped.getPlayTimes();
        } catch (RuntimeException e) {
            mapper = "error=" + e.getClass().getSimpleName() + ": " + firstLine(e);
        }
        System.out.printf("[SQL-BASELINE] VideoStatusMapper.selectByVid of 3e9 -> %s%n", mapper);

        // Baseline claim: never silently wrong. Either the read fails, or the value is exactly 3000000001.
        if (mapped != null) {
            assertEquals(3_000_000_001L, mapped.getPlayTimes().longValue(),
                    "Integer mapping must not silently corrupt 3e9: " + mapper);
        } else {
            assertTrue(mapper.startsWith("error="), "unexpected mapper result: " + mapper);
        }
    }

    /**
     * Characterises the upper bound: MySQL accepts {@code Long.MAX_VALUE + 1} in the UNSIGNED column, but Java
     * {@code long} cannot represent it. T02 is expected to reject such values before they are stored; this test
     * only records the current state and must not be read as "the limit is implemented".
     */
    @Test
    void longMaxPlusOneIsAcceptedByMysqlButNotRepresentableAsLong() {
        setPlayTimes(Long.MAX_VALUE);
        String outcome = attempt("guarded Long.MAX + 1", GUARDED_DELTA, 1L, vid, 1L);
        BigDecimal stored = playTimes();
        System.out.printf("[SQL-BASELINE] after Long.MAX + 1: %s stored=%s%n", outcome, stored.toPlainString());

        String longRead;
        Long longValue = null;
        try {
            longValue = jdbc.queryForObject("SELECT play_times FROM video_status WHERE vid = ?", Long.class, vid);
            longRead = "value=" + longValue;
        } catch (RuntimeException e) {
            longRead = "error=" + e.getClass().getSimpleName() + ": " + firstLine(e);
        }
        System.out.printf("[SQL-BASELINE] Long read of stored value -> %s%n", longRead);

        assertEquals("rows=1", outcome, "current baseline: MySQL accepts Long.MAX + 1");
        assertEquals(0, new BigDecimal("9223372036854775808").compareTo(stored),
                "current baseline: BIGINT UNSIGNED stores 2^63");
        // The Java side must never report a *different* number silently.
        if (longValue != null) {
            assertEquals(Long.MAX_VALUE, longValue.longValue(),
                    "a Long read must not silently wrap: " + longRead);
        } else {
            assertTrue(longRead.startsWith("error="), "unexpected Long read result: " + longRead);
        }
    }

    @Test
    void schemaHasNoCheckConstraintsOnCounters() {
        Integer checks = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME LIKE '%video_status%'", Integer.class);
        assertEquals(0, checks, "video_status relies on UNSIGNED alone; document this in the T02 migration plan");
    }
}
