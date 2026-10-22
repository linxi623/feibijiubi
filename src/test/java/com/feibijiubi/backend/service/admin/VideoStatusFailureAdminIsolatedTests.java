package com.feibijiubi.backend.service.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.mapper.VideoStatusFailureAdminMapper;
import com.feibijiubi.backend.service.admin.VideoStatusFailureAdminRow.ConsumedRow;
import com.feibijiubi.backend.service.admin.VideoStatusFailureAdminRow.OutboxRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T10 ACKNOWLEDGE_FAILURE real isolated integration test.
 *
 * <p>Only {@code compose.test.yaml} MySQL 13307 is allowed. RabbitMQ/Redis/Redisson auto
 * configuration is excluded: this test never touches business MQ or Redis and never purges or
 * deletes any row. Each case adds its own user/video/outbox/consumed/audit rows.</p>
 *
 * <p>Covered invariants:</p>
 * <ol>
 *   <li>real mapper keyset pagination for OUTBOX and CONSUMED;</li>
 *   <li>safe projection excludes payload / lease_token / raw last_error;</li>
 *   <li>audit idempotency by operationId; duplicate returns the persisted original result;</li>
 *   <li>acknowledge never changes the target row, counters, consumed ledger or Redis;</li>
 *   <li>only Outbox FAILED (status=3) and consumed status3 are writable; consumed status0 is
 *       read-only and every other status is rejected;</li>
 *   <li>concurrent duplicate operationId yields exactly one COMPLETED audit row;</li>
 *   <li>an audit insert failure rolls back the real MySQL transaction and leaves no audit row.</li>
 * </ol>
 *
 * <p>Run: {@code ./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusFailureAdminIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusFailureAdminIsolatedTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusFailureAdminIsolatedTests.IsolationGuard.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VideoStatusFailureAdminIsolatedTests {

    private static final String ACTION = VideoStatusFailureAdminService.ACTION_ACKNOWLEDGE_FAILURE;
    private static final String OPERATOR = "42";
    private static final String PAYLOAD_SENTINEL = "T10_PAYLOAD_SENTINEL";
    private static final String LAST_ERROR_SENTINEL = "T10_LAST_ERROR_SENTINEL";
    private static final String LEASE_SENTINEL = "T10_LEASE_TOKEN_SENTINEL";

    /** Rejects every route to a non-isolated datasource, Redis or RabbitMQ endpoint. */
    static class IsolationGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var env = context.getEnvironment();
            Map<String, String> required = Map.of(
                    "spring.datasource.url", "jdbc:mysql://127.0.0.1:13307/feibijiubi?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                    "spring.datasource.username", "root",
                    "spring.datasource.password", "isolated-test-only",
                    "spring.data.redis.host", "127.0.0.1",
                    "spring.data.redis.port", "16379",
                    "spring.rabbitmq.host", "127.0.0.1",
                    "spring.rabbitmq.port", "15673",
                    "spring.rabbitmq.username", "isolated-test",
                    "spring.rabbitmq.password", "isolated-test-only");
            required.forEach((key, expected) -> {
                if (!expected.equals(env.getProperty(key))) {
                    throw new IllegalStateException("Refusing non-isolated test configuration: " + key);
                }
            });
            for (String key : List.of("spring.datasource.jndi-name",
                    "spring.datasource.hikari.jdbc-url",
                    "spring.data.redis.url", "spring.data.redis.cluster.nodes",
                    "spring.data.redis.sentinel.master", "spring.rabbitmq.addresses")) {
                if (env.containsProperty(key)) {
                    throw new IllegalStateException("Unexpected connection override: " + key);
                }
            }
            // Whatever the spelling (env-var style included), Redis may only use the isolated host/port.
            for (var source : env.getPropertySources()) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                for (String name : enumerable.getPropertyNames()) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    for (String candidate : List.of(lower, lower.replace('_', '.'))) {
                        if (!candidate.startsWith("spring.data.redis")) {
                            continue;
                        }
                        if (!"spring.data.redis.host".equals(candidate)
                                && !"spring.data.redis.port".equals(candidate)) {
                            throw new IllegalStateException("Unexpected Redis override: " + name);
                        }
                    }
                }
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
    @MapperScan(basePackageClasses = VideoStatusFailureAdminMapper.class)
    @EnableConfigurationProperties(VideoStatusProperties.class)
    @Import(VideoStatusFailureAdminServiceImpl.class)
    static class TestApplication {
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    VideoStatusFailureAdminService service;
    @Autowired
    VideoStatusFailureAdminMapper mapper;
    @Autowired
    VideoStatusProperties properties;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    ObjectMapper objectMapper;

    int vid;

    @BeforeAll
    void assertIsolatedEndpointAndSchema() throws Exception {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                            .startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"),
                    "the isolated MySQL endpoint must be the only target");
        }
        // A read-only marker proves the T10 audit table exists on the isolated schema.
        assertNotNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_consumption_repair_log", Long.class));
        assertTrue(properties.getConsumerRecoveryAutoReplayMaxAgeSeconds() > 0,
                "safety window must be configured positive");
    }

    /** Every case gets independent rows; nothing is deleted, purged or reused. */
    @BeforeEach
    void seedVideo() {
        String username = "t10ack-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')",
                username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?",
                Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                        + "VALUES (?, 't10-ack', 'test', 'test', '', '', '', '')",
                uid);
        vid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid, play_times, like_times) VALUES (?, 11, 7)", vid);
    }

    // ------------------------------------------------------- real mapper pagination

    @Test
    void failureListIndexesExistInKeyOrderAndPaginationQueriesUseThem() {
        assertEquals(List.of("status", "id"), jdbc.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'video_status_outbox' "
                        + "AND index_name = 'idx_video_status_outbox_status_id' "
                        + "ORDER BY seq_in_index", String.class),
                "outbox failure pagination requires (status, id) in that order");
        assertEquals(List.of("process_status", "id"), jdbc.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'video_status_consumed_event' "
                        + "AND index_name = 'idx_video_status_consumed_status_id' "
                        + "ORDER BY seq_in_index", String.class),
                "consumed failure pagination requires (process_status, id) in that order");

        List<Map<String, Object>> outboxExplain = jdbc.queryForList(
                "EXPLAIN SELECT id FROM video_status_outbox "
                        + "FORCE INDEX (idx_video_status_outbox_status_id) "
                        + "WHERE status = 3 AND id > 0 ORDER BY id LIMIT 21");
        assertTrue(outboxExplain.stream()
                        .filter(row -> "video_status_outbox".equals(row.get("table")))
                        .allMatch(row -> "idx_video_status_outbox_status_id"
                                .equals(row.get("key"))),
                "outbox failure page must be a bounded (status, id) index range: "
                        + outboxExplain);

        List<Map<String, Object>> consumedExplain = jdbc.queryForList(
                "EXPLAIN SELECT paged.* FROM ("
                        + "(SELECT id FROM video_status_consumed_event "
                        + "FORCE INDEX (idx_video_status_consumed_status_id) "
                        + "WHERE process_status = 0 AND id > 0 ORDER BY id LIMIT 21) "
                        + "UNION ALL "
                        + "(SELECT id FROM video_status_consumed_event "
                        + "FORCE INDEX (idx_video_status_consumed_status_id) "
                        + "WHERE process_status = 3 AND id > 0 ORDER BY id LIMIT 21)"
                        + ") paged ORDER BY paged.id LIMIT 21");
        assertTrue(consumedExplain.stream()
                        .filter(row -> "video_status_consumed_event".equals(row.get("table")))
                        .allMatch(row -> "idx_video_status_consumed_status_id"
                                .equals(row.get("key"))),
                "both consumed single-status branches must use (process_status, id): "
                        + consumedExplain);
    }

    @Test
    void outboxPaginationUsesRealKeysetMapperWithBoundedPageAndNextCursor() {
        long baseId = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id),0) FROM video_status_outbox", Long.class);
        String event1 = newEventId();
        String event2 = newEventId();
        String event3 = newEventId();
        insertOutbox(event1, 3, 5);
        insertOutbox(event2, 3, 5);
        insertOutbox(event3, 3, 5);

        VideoStatusFailureAdminService.Page first = service.list("outbox", baseId, 2);

        assertEquals("OUTBOX", first.source());
        assertEquals(baseId, first.afterId());
        assertEquals(2, first.limit());
        assertEquals(2, first.items().size());
        assertTrue(first.hasMore());
        assertNotNull(first.nextAfterId());
        assertEquals(event1, first.items().get(0).eventId());
        assertEquals(event2, first.items().get(1).eventId());
        assertEquals(first.items().get(1).id(), first.nextAfterId());
        assertEquals("FAILED", first.items().get(0).statusName());
        assertEquals(3, first.items().get(0).status());

        VideoStatusFailureAdminService.Page second =
                service.list("OUTBOX", first.nextAfterId(), 2);

        assertEquals(1, second.items().size());
        assertFalse(second.hasMore());
        assertNull(second.nextAfterId());
        assertEquals(event3, second.items().get(0).eventId());

        // Real mapper returns rows ordered by id, never by offset; the cursor is the last safe id.
        List<OutboxRow> direct = mapper.selectFailedOutbox(first.nextAfterId(), 3);
        assertEquals(1, direct.size());
        assertEquals(event3, direct.get(0).getEventId());
    }

    @Test
    void consumedPaginationIncludesStatus0OnlyForReadDiagnosis() {
        long baseId = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id),0) FROM video_status_consumed_event", Long.class);
        String repair = newEventId();
        String received = newEventId();
        String repair2 = newEventId();
        insertConsumed(repair, 3);
        insertConsumed(received, 0);
        insertConsumed(repair2, 3);

        VideoStatusFailureAdminService.Page first = service.list("CONSUMED", baseId, 2);

        assertEquals(2, first.items().size());
        assertTrue(first.hasMore());
        assertEquals(repair, first.items().get(0).eventId());
        assertEquals("REPAIR_REQUIRED", first.items().get(0).statusName());
        assertEquals(received, first.items().get(1).eventId());
        assertEquals("RECEIVED", first.items().get(1).statusName());
        assertEquals(VideoStatusFailureAdminServiceImpl.REASON_CONSUMED_STATUS0_DIAGNOSTIC_ONLY,
                first.items().get(1).reasonCode());

        VideoStatusFailureAdminService.Page second =
                service.list("CONSUMED", first.nextAfterId(), 2);

        assertEquals(1, second.items().size());
        assertFalse(second.hasMore());
        assertEquals(repair2, second.items().get(0).eventId());
    }

    // ------------------------------------------------------------- safe projection

    @Test
    void listAndDetailNeverExposePayloadLeaseTokenOrRawLastError() throws Exception {
        String eventId = newEventId();
        insertOutbox(eventId, 3, 5);
        jdbc.update("UPDATE video_status_outbox SET payload = ?, lease_token = ?, last_error = ? "
                        + "WHERE event_id = ?",
                "{\"secret\":\"" + PAYLOAD_SENTINEL + "\"}", LEASE_SENTINEL,
                "raw failure " + LAST_ERROR_SENTINEL, eventId);

        String consumedId = newEventId();
        insertConsumed(consumedId, 3);
        jdbc.update("UPDATE video_status_consumed_event SET payload = ?, last_error = ? "
                        + "WHERE event_id = ?",
                "{\"secret\":\"" + PAYLOAD_SENTINEL + "\"}", "raw failure " + LAST_ERROR_SENTINEL,
                consumedId);

        VideoStatusFailureAdminService.Detail outbox = service.detail("OUTBOX", eventId);
        VideoStatusFailureAdminService.Detail consumed = service.detail("CONSUMED", consumedId);

        assertTrue(outbox.item().lastErrorPresent());
        assertNotNull(outbox.item().lastErrorHint());
        assertTrue(consumed.item().lastErrorPresent());
        assertNotNull(consumed.item().lastErrorHint());

        String serialized = objectMapper.writeValueAsString(List.of(outbox, consumed));
        assertFalse(serialized.contains(PAYLOAD_SENTINEL), serialized);
        assertFalse(serialized.contains(LAST_ERROR_SENTINEL), serialized);
        assertFalse(serialized.contains(LEASE_SENTINEL), serialized);

        // The safe row DTOs do not even have fields capable of carrying those raw values.
        for (Field field : OutboxRow.class.getDeclaredFields()) {
            assertFalse(field.getName().equalsIgnoreCase("payload"), field.getName());
            assertFalse(field.getName().equalsIgnoreCase("leaseToken"), field.getName());
            assertFalse(field.getName().equalsIgnoreCase("lastError"), field.getName());
        }
        for (Field field : ConsumedRow.class.getDeclaredFields()) {
            assertFalse(field.getName().equalsIgnoreCase("payload"), field.getName());
            assertFalse(field.getName().equalsIgnoreCase("lastError"), field.getName());
        }
    }

    // ----------------------------------------------- idempotency and unchanged target

    @Test
    void acknowledgeIsIdempotentByOperationIdAndNeverChangesTarget() throws Exception {
        String eventId = newEventId();
        insertOutbox(eventId, 3, 5);
        jdbc.update("UPDATE video_status_outbox SET last_error = ?, lease_token = ? WHERE event_id = ?",
                "PUBLISH_FAILED " + LAST_ERROR_SENTINEL, LEASE_SENTINEL, eventId);
        Map<String, Object> targetBefore = outboxRow(eventId);
        Map<String, Object> countersBefore = counterRow();
        String operationId = "op-" + UUID.randomUUID();
        String reason = "确认已阅，等待受控对账";

        VideoStatusFailureAdminService.AcknowledgeResult first =
                service.acknowledge("OUTBOX", eventId, operationId, reason, OPERATOR);

        assertTrue(first.acknowledgedApplied());
        assertFalse(first.duplicate());
        assertEquals(ACTION, first.action());
        assertEquals(eventId, first.eventId());
        assertEquals(OPERATOR, first.operator());
        assertEquals("COMPLETED", first.operationStatus());
        assertEquals(3, first.statusBefore());
        assertEquals(3, first.statusAfter(), "before/after must prove no target change");
        assertNull(first.processStatusBefore());
        assertNull(first.processStatusAfter());
        assertNotNull(first.completedAt());

        Map<String, Object> audit = auditRow(operationId);
        assertEquals(1, ((Number) audit.get("operation_status")).intValue());
        assertEquals(ACTION, audit.get("action"));
        assertEquals(eventId, audit.get("event_id"));
        assertEquals(OPERATOR, audit.get("operator"));
        assertEquals(reason, audit.get("reason"));
        assertNotNull(audit.get("completed_at"));
        String metadataJson = String.valueOf(audit.get("last_error"));
        assertFalse(metadataJson.contains(LAST_ERROR_SENTINEL));
        assertFalse(metadataJson.contains(PAYLOAD_SENTINEL));
        Map<String, Object> metadata = auditMetadata(operationId);
        assertEquals("OUTBOX", metadata.get("source"));
        assertEquals(3, ((Number) metadata.get("statusBefore")).intValue());
        assertEquals(3, ((Number) metadata.get("statusAfter")).intValue());

        assertEquals(targetBefore, outboxRow(eventId),
                "acknowledge must not modify status/counters/lease_token/last_error/payload");
        assertEquals(countersBefore, counterRow());

        VideoStatusFailureAdminService.AcknowledgeResult second =
                service.acknowledge("OUTBOX", eventId, operationId, reason, OPERATOR);

        assertTrue(second.duplicate());
        assertFalse(second.acknowledgedApplied());
        assertNotNull(second.completedAt(),
                "duplicate must return the persisted completion time");
        assertFalse(second.completedAt().isAfter(LocalDateTime.now()),
                "the persisted completion time must not be replaced by a future time");
        assertEquals(3, second.statusBefore());
        assertEquals(3, second.statusAfter());
        assertEquals(1, auditCount(operationId), "duplicate operationId must not insert again");
        assertEquals(targetBefore, outboxRow(eventId));
        assertEquals(countersBefore, counterRow());

        BusinessException reasonConflict = assertThrows(BusinessException.class,
                () -> service.acknowledge("OUTBOX", eventId, operationId, "换一个原因", OPERATOR));
        assertEquals(409, reasonConflict.getCode());
        assertEquals(1, auditCount(operationId));
        assertEquals(reason, auditRow(operationId).get("reason"));
        assertEquals(targetBefore, outboxRow(eventId));
    }

    @Test
    void acknowledgeConsumedStatus3AndRejectStatus0AsReadOnly() throws Exception {
        String repairEvent = newEventId();
        insertConsumed(repairEvent, 3);
        jdbc.update("UPDATE video_status_consumed_event SET last_error = ? WHERE event_id = ?",
                "CONSUMER_FAILED " + LAST_ERROR_SENTINEL, repairEvent);
        Map<String, Object> repairBefore = consumedRow(repairEvent);
        String repairOperation = "op-" + UUID.randomUUID();

        VideoStatusFailureAdminService.AcknowledgeResult result = service.acknowledge(
                "CONSUMED", repairEvent, repairOperation, "确认 status3 已阅", OPERATOR);

        assertTrue(result.acknowledgedApplied());
        assertFalse(result.duplicate());
        assertEquals(ACTION, result.action());
        assertNull(result.statusBefore());
        assertNull(result.statusAfter());
        assertEquals(3, result.processStatusBefore());
        assertEquals(3, result.processStatusAfter());
        assertEquals(1, auditCount(repairOperation));
        assertEquals(repairBefore, consumedRow(repairEvent),
                "consumed status3 acknowledgement must not change process_status or payload/evidence");

        String status0Event = newEventId();
        insertConsumed(status0Event, 0);
        Map<String, Object> status0Before = consumedRow(status0Event);
        String status0Operation = "op-" + UUID.randomUUID();

        BusinessException status0Rejected = assertThrows(BusinessException.class,
                () -> service.acknowledge("CONSUMED", status0Event, status0Operation,
                        "status0 只读，不能确认", OPERATOR));

        assertEquals(409, status0Rejected.getCode());
        assertTrue(status0Rejected.getMessage().contains("status0"), status0Rejected.getMessage());
        assertEquals(0, auditCount(status0Operation),
                "rejected status0 must not write an audit row");
        assertEquals(status0Before, consumedRow(status0Event));
    }

    @Test
    void onlyOutboxFailedAndConsumedRepairRequiredAreWritable() {
        for (int outboxStatus : new int[]{0, 1, 2}) {
            String eventId = newEventId();
            insertOutbox(eventId, outboxStatus, 5);
            Map<String, Object> before = outboxRow(eventId);
            String operationId = "op-" + UUID.randomUUID();

            BusinessException rejected = assertThrows(BusinessException.class,
                    () -> service.acknowledge("OUTBOX", eventId, operationId, "不是 FAILED", OPERATOR));

            assertEquals(404, rejected.getCode(), "outbox status=" + outboxStatus + " is not eligible");
            assertEquals(0, auditCount(operationId));
            assertEquals(before, outboxRow(eventId));
        }

        for (int consumedStatus : new int[]{1, 2}) {
            String eventId = newEventId();
            insertConsumed(eventId, consumedStatus);
            Map<String, Object> before = consumedRow(eventId);
            String operationId = "op-" + UUID.randomUUID();

            BusinessException rejected = assertThrows(BusinessException.class,
                    () -> service.acknowledge("CONSUMED", eventId, operationId,
                            "不是 status3", OPERATOR));

            assertEquals(409, rejected.getCode(),
                    "consumed process_status=" + consumedStatus + " is not eligible");
            assertEquals(0, auditCount(operationId));
            assertEquals(before, consumedRow(eventId));
        }
    }

    // --------------------------------------------------- concurrent duplicate opId

    @Test
    void concurrentDuplicateOperationIdProducesExactlyOneCompletedAudit() throws Exception {
        String eventId = newEventId();
        insertOutbox(eventId, 3, 5);
        Map<String, Object> targetBefore = outboxRow(eventId);
        String operationId = "op-" + UUID.randomUUID();
        String reason = "并发重复提交同一 operationId";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = List.of(
                    pool.submit(() -> acknowledgeConcurrently(
                            ready, start, eventId, operationId, reason)),
                    pool.submit(() -> acknowledgeConcurrently(
                            ready, start, eventId, operationId, reason)));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "callers did not become ready");
            start.countDown();

            int applied = 0;
            int duplicate = 0;
            int conflicts = 0;
            for (Future<Object> future : futures) {
                Object outcome = future.get(20, TimeUnit.SECONDS);
                if (outcome instanceof VideoStatusFailureAdminService.AcknowledgeResult result) {
                    if (result.acknowledgedApplied()) {
                        applied++;
                    }
                    if (result.duplicate()) {
                        duplicate++;
                    }
                } else if (outcome instanceof BusinessException error) {
                    assertEquals(409, error.getCode(), error.getMessage());
                    conflicts++;
                } else {
                    throw new AssertionError("unexpected concurrent outcome: " + outcome);
                }
            }

            assertEquals(1, applied, "only one caller may apply the acknowledgement");
            assertEquals(1, duplicate + conflicts,
                    "the other caller must be a duplicate result or a 409 conflict");
            assertEquals(1, auditCount(operationId), "exactly one COMPLETED audit row");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(ACTION, auditRow(operationId).get("action"));
        assertEquals(targetBefore, outboxRow(eventId),
                "concurrent duplicate operationId must not modify the target");
    }

    private Object acknowledgeConcurrently(CountDownLatch ready, CountDownLatch start,
                                           String eventId, String operationId, String reason)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent start timed out");
        }
        try {
            return service.acknowledge("OUTBOX", eventId, operationId, reason, OPERATOR);
        } catch (BusinessException e) {
            return e;
        }
    }

    // ------------------------------------------------- audit failure rolls back

    @Test
    void auditInsertFailureRollsBackRealMySqlTransactionWithoutAuditOrTargetChange() {
        String eventId = newEventId();
        insertOutbox(eventId, 3, 5);
        Map<String, Object> targetBefore = outboxRow(eventId);
        String operationId = "op-" + UUID.randomUUID();

        VideoStatusFailureAdminMapper failingMapper =
                (VideoStatusFailureAdminMapper) Proxy.newProxyInstance(
                        VideoStatusFailureAdminMapper.class.getClassLoader(),
                        new Class<?>[]{VideoStatusFailureAdminMapper.class},
                        (proxy, method, args) -> {
                            if ("insertCompletedRepairLog".equals(method.getName())) {
                                throw new DataIntegrityViolationException(
                                        "simulated audit insert failure in real MySQL transaction");
                            }
                            try {
                                return method.invoke(mapper, args);
                            } catch (InvocationTargetException e) {
                                throw e.getTargetException();
                            }
                        });
        VideoStatusFailureAdminServiceImpl failingService =
                new VideoStatusFailureAdminServiceImpl(failingMapper, properties, objectMapper);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThrows(DataIntegrityViolationException.class,
                () -> template.executeWithoutResult(status -> failingService.acknowledge(
                        "OUTBOX", eventId, operationId, "审计写入失败必须回滚", OPERATOR)));

        assertEquals(0, auditCount(operationId), "failed audit must leave no COMPLETED row");
        assertEquals(targetBefore, outboxRow(eventId),
                "failed audit must not change the target");

        // The same operationId is still usable afterwards: the failed insert left no unique key.
        VideoStatusFailureAdminService.AcknowledgeResult retried =
                service.acknowledge("OUTBOX", eventId, operationId, "审计失败后重试", OPERATOR);
        assertTrue(retried.acknowledgedApplied());
        assertEquals(1, auditCount(operationId));
        assertEquals(targetBefore, outboxRow(eventId));
    }

    // ---------------------------------------------------------------- helpers

    private String newEventId() {
        return "t10ack-" + UUID.randomUUID();
    }

    private void insertOutbox(String eventId, int status, long ageSeconds) {
        jdbc.update("INSERT INTO video_status_outbox("
                        + "event_id, aggregate_id, event_type, payload, status, retry_count, "
                        + "lease_recovery_count, confirm_writeback_count, last_error, created_at) "
                        + "VALUES (?, ?, 'PLAY', '{\"delta\":1}', ?, 5, 2, 4, 'PUBLISH_FAILED', "
                        + "TIMESTAMPADD(SECOND, -?, NOW(3)))",
                eventId, vid, status, ageSeconds);
    }

    private void insertConsumed(String eventId, int processStatus) {
        jdbc.update("INSERT INTO video_status_consumed_event("
                        + "event_id, vid, event_type, delta, payload, payload_hash, process_status) "
                        + "VALUES (?, ?, 'PLAY', 1, '{\"delta\":1}', ?, ?)",
                eventId, vid, "0".repeat(64), processStatus);
    }

    private Map<String, Object> outboxRow(String eventId) {
        return new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT event_id, aggregate_id, event_type, payload, status, retry_count, "
                        + "lease_recovery_count, confirm_writeback_count, next_retry_at, sending_at, "
                        + "lease_token, last_error, created_at, sent_at "
                        + "FROM video_status_outbox WHERE event_id = ?", eventId));
    }

    private Map<String, Object> consumedRow(String eventId) {
        return new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT event_id, vid, event_type, delta, payload, payload_hash, process_status, "
                        + "consumer_retry_count, last_attempt_at, last_error, consumed_at, "
                        + "redis_applied_at, flushed_at "
                        + "FROM video_status_consumed_event WHERE event_id = ?", eventId));
    }

    private Map<String, Object> counterRow() {
        return new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT vid, play_times, like_times, coin_times, collect_times, share_times "
                        + "FROM video_status WHERE vid = ?", vid));
    }

    private Map<String, Object> auditRow(String operationId) {
        return jdbc.queryForMap("SELECT operation_id, event_id, action, operation_status, reason, "
                + "operator, last_error, created_at, completed_at "
                + "FROM video_status_consumption_repair_log WHERE operation_id = ?", operationId);
    }

    private Map<String, Object> auditMetadata(String operationId) throws Exception {
        Object lastError = auditRow(operationId).get("last_error");
        assertNotNull(lastError, "acknowledge must persist before/after metadata in last_error");
        return objectMapper.readValue(String.valueOf(lastError),
                new TypeReference<Map<String, Object>>() {
                });
    }

    private int auditCount(String operationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_consumption_repair_log WHERE operation_id = ?",
                Integer.class, operationId);
        return count == null ? 0 : count;
    }
}
