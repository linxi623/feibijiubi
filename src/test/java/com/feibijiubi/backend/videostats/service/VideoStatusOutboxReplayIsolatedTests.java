package com.feibijiubi.backend.videostats.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.videostats.mapper.VideoStatusOutboxRepairMapper;
import com.feibijiubi.backend.videostats.service.admin.VideoStatusOutboxReplayService;
import com.feibijiubi.backend.videostats.vo.VideoStatusOutboxReplayVO;
import com.feibijiubi.backend.videostats.vo.VideoStatusOutboxStatusVO;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T07 临时运维（T10 极窄子集）真实隔离验收：只连 compose.test.yaml 的 MySQL 13307，
 * RabbitMQ 自动配置被排除，不连业务 MQ、不写 Redis、不清理他人数据。
 *
 * <p>验收的不变量（与 runbook 一一对应）：</p>
 * <ol>
 *   <li><b>审计与状态同事务</b>：外层事务回滚后，outbox 仍是 FAILED 且 audit 行数为 0；
 *       审计插入失败（真实 MySQL 事务）同样把状态重置一起回滚；</li>
 *   <li><b>任何 consumed 都拒绝</b>：process_status 0/1/2/3 一律 409，不改 consumed/统计/current；</li>
 *   <li><b>幂等以 reason 为条件</b>：operationId 完成后再用不同 reason 提交 → 409，不伪装原结果；</li>
 *   <li><b>before/after 持久化</b>：写入 repair_log.last_error 元数据；duplicate 从元数据重放原始
 *       before/after，同时 current* 展示实时状态；</li>
 *   <li><b>双重安全窗口</b>：created_at（DB 时钟）与 occurredAt（生产端 JVM 时钟）都必须在窗口内，
 *       边界等于上限也拒绝；未来 occurredAt 拒绝；</li>
 *   <li><b>GET 安全</b>：不返回 payload / leaseToken / last_error 原文。</li>
 * </ol>
 *
 * <p>运行：{@code ./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusOutboxReplayIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusOutboxReplayIsolatedTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusCounterIsolatedTests.IsolationGuard.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VideoStatusOutboxReplayIsolatedTests {

    private static final String ACTION = VideoStatusOutboxReplayService.ACTION_REPLAY_TO_PENDING;
    private static final String METADATA_SCHEMA = "T07_REPLAY_META_V2";
    private static final int SEEDED_RETRY_COUNT = 5;
    private static final int SEEDED_LEASE_RECOVERY_COUNT = 2;
    private static final int SEEDED_CONFIRM_WRITEBACK_COUNT = 4;
    private static final String OPERATOR = "42";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
    @MapperScan(basePackageClasses = VideoStatusOutboxRepairMapper.class)
    @EnableConfigurationProperties(VideoStatusProperties.class)
    @Import(VideoStatusOutboxReplayService.class)
    static class TestApplication {
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    VideoStatusOutboxReplayService replayService;
    @Autowired
    VideoStatusOutboxRepairMapper repairMapper;
    @Autowired
    VideoStatusProperties properties;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    ObjectMapper objectMapper;

    int vid;

    @BeforeAll
    void assertIsolatedEndpoint() throws Exception {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                            .startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"),
                    "the isolated MySQL endpoint must be the only target");
        }
        assertTrue(properties.getConsumerRecoveryAutoReplayMaxAgeSeconds() > 0,
                "safety window must be configured positive");
    }

    /** 每个用例一个随机新 vid 与独立 vids；只新增，不删除任何历史行。 */
    @BeforeEach
    void seedVideo() {
        String username = "t07repair-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                        + "VALUES (?, 't07-repair', 'test', 'test', '', '', '', '')",
                uid);
        vid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid, play_times) VALUES (?, 11)", vid);
    }

    // ------------------------------------------------------------ 1) 同事务审计

    @Test
    void committedReplayResetsBudgetsAndPersistsBeforeAfterMetadata() throws Exception {
        String eventId = newEventId();
        String payload = validPayload(eventId);
        insertOutbox(eventId, 3, payload, SEEDED_RETRY_COUNT, SEEDED_LEASE_RECOVERY_COUNT, 10);
        String payloadAsStored = String.valueOf(outboxRow(eventId).get("payload"));

        Map<String, Object> countersBefore = counterRow();

        VideoStatusOutboxReplayVO vo = replayService.replay(
                eventId, "op-" + UUID.randomUUID(), "confirm 超时后人工重放", OPERATOR);

        assertTrue(vo.isReplayApplied());
        assertFalse(vo.isDuplicate());
        assertEquals(SEEDED_RETRY_COUNT, vo.getRetryCountBefore());
        assertEquals(0, vo.getRetryCountAfter());
        assertEquals(SEEDED_LEASE_RECOVERY_COUNT, vo.getLeaseRecoveryCountBefore());
        assertEquals(0, vo.getLeaseRecoveryCountAfter());
        assertEquals(SEEDED_CONFIRM_WRITEBACK_COUNT, vo.getConfirmWritebackCountBefore());
        assertEquals(0, vo.getConfirmWritebackCountAfter());
        assertEquals("FAILED", vo.getStatusNameBefore());
        assertEquals("PENDING", vo.getStatusNameAfter());
        assertEquals("PENDING", vo.getCurrentStatusName());
        assertEquals(0, vo.getCurrentRetryCount());
        assertEquals(0, vo.getCurrentLeaseRecoveryCount());
        assertEquals(0, vo.getCurrentConfirmWritebackCount());
        assertEquals(ACTION, vo.getAction());
        assertEquals(OPERATOR, vo.getOperator());

        Map<String, Object> row = outboxRow(eventId);
        assertEquals(0, ((Number) row.get("status")).intValue());
        assertEquals(0, ((Number) row.get("retry_count")).intValue());
        assertEquals(0, ((Number) row.get("lease_recovery_count")).intValue());
        assertEquals(0, ((Number) row.get("confirm_writeback_count")).intValue());
        assertNull(row.get("next_retry_at"));
        assertNull(row.get("sending_at"));
        assertNull(row.get("lease_token"));
        assertEquals(payloadAsStored, row.get("payload"), "payload 必须原样保留");
        assertEquals(eventId, row.get("event_id"), "eventId 必须原样保留，不许新建 eventId");
        assertEquals("PUBLISH_CONFIRM_TIMEOUT", row.get("last_error"), "失败证据不清除");

        assertEquals(0, consumedCount(eventId), "本工具绝不写消费账本");
        assertEquals(countersBefore, counterRow(), "不允许修改 video_status 计数");

        Map<String, Object> audit = auditRow(vo.getOperationId());
        assertEquals(1, ((Number) audit.get("operation_status")).intValue(), "审计必须是 COMPLETED");
        assertEquals(ACTION, audit.get("action"));
        assertEquals(eventId, audit.get("event_id"));
        assertEquals(OPERATOR, audit.get("operator"));
        assertEquals("confirm 超时后人工重放", audit.get("reason"));
        assertNotNull(audit.get("completed_at"));

        Map<String, Object> metadata = auditMetadata(vo.getOperationId());
        assertEquals(METADATA_SCHEMA, metadata.get("schema"));
        assertEquals(3, ((Number) metadata.get("statusBefore")).intValue());
        assertEquals(0, ((Number) metadata.get("statusAfter")).intValue());
        assertEquals(SEEDED_RETRY_COUNT, ((Number) metadata.get("retryBefore")).intValue());
        assertEquals(0, ((Number) metadata.get("retryAfter")).intValue());
        assertEquals(SEEDED_LEASE_RECOVERY_COUNT,
                ((Number) metadata.get("leaseRecoveryBefore")).intValue());
        assertEquals(0, ((Number) metadata.get("leaseRecoveryAfter")).intValue());
        assertEquals(SEEDED_CONFIRM_WRITEBACK_COUNT,
                ((Number) metadata.get("confirmWritebackBefore")).intValue());
        assertEquals(0, ((Number) metadata.get("confirmWritebackAfter")).intValue());
        String metadataJson = String.valueOf(audit.get("last_error"));
        assertFalse(metadataJson.contains("payload"), "元数据不含 payload");
        assertFalse(metadataJson.contains("hotScoreDelta"), "元数据不含 payload 明细");

        System.out.printf(Locale.ROOT,
                "[T07-repair-IT] replay committed: vid=%d retry %d->0 lease %d->0 audit=%s%n",
                vid, SEEDED_RETRY_COUNT, SEEDED_LEASE_RECOVERY_COUNT, vo.getOperationId());
    }

    @Test
    void rolledBackOuterTransactionKeepsFailedAndLeavesNoAudit() throws Exception {
        String eventId = newEventId();
        insertOutbox(eventId, 3, validPayload(eventId),
                SEEDED_RETRY_COUNT, SEEDED_LEASE_RECOVERY_COUNT, 10);
        String operationId = "op-" + UUID.randomUUID();

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            replayService.replay(eventId, operationId, "测试同事务回滚", OPERATOR);
            // 事务内可见：说明下面的回滚证据不是“什么都没做”
            assertEquals(0, ((Number) outboxRow(eventId).get("status")).intValue());
            assertEquals(1, auditCount(operationId));
            status.setRollbackOnly();
        });

        assertEquals(3, ((Number) outboxRow(eventId).get("status")).intValue(),
                "审计与状态必须一起回滚：outbox 仍为 FAILED");
        assertEquals(SEEDED_RETRY_COUNT, ((Number) outboxRow(eventId).get("retry_count")).intValue());
        assertEquals(0, auditCount(operationId), "回滚后不得残留 COMPLETED 审计");
    }

    /**
     * 审计插入失败必须真实回滚状态重置。
     *
     * <p>用真实 MySQL 事务 + 一个只在 {@code insertCompletedRepairLog} 抛
     * {@link DataIntegrityViolationException} 的 mapper 代理（其余语句全部委托真实 mapper），
     * 模拟审计表写入失败：状态重置与审计在同一事务里，异常必须把 CAS 一起回滚。</p>
     */
    @Test
    void auditInsertFailureRollsBackStatusResetInRealTransaction() {
        String eventId = newEventId();
        insertOutbox(eventId, 3, validPayload(eventId),
                SEEDED_RETRY_COUNT, SEEDED_LEASE_RECOVERY_COUNT, 10);
        String operationId = "op-" + UUID.randomUUID();

        VideoStatusOutboxRepairMapper failingMapper =
                (VideoStatusOutboxRepairMapper) Proxy.newProxyInstance(
                        VideoStatusOutboxRepairMapper.class.getClassLoader(),
                        new Class<?>[]{VideoStatusOutboxRepairMapper.class},
                        (proxy, method, args) -> {
                            if ("insertCompletedRepairLog".equals(method.getName())) {
                                throw new DataIntegrityViolationException(
                                        "simulated repair_log insert failure (real MySQL transaction)");
                            }
                            try {
                                return method.invoke(repairMapper, args);
                            } catch (InvocationTargetException e) {
                                throw e.getTargetException();
                            }
                        });
        VideoStatusOutboxReplayService failingService =
                new VideoStatusOutboxReplayService(failingMapper, properties, objectMapper);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThrows(DataIntegrityViolationException.class,
                () -> template.executeWithoutResult(status -> failingService.replay(
                        eventId, operationId, "审计写入失败回滚", OPERATOR)));

        Map<String, Object> row = outboxRow(eventId);
        assertEquals(3, ((Number) row.get("status")).intValue(),
                "审计插入失败必须回滚 CAS：outbox 仍为 FAILED");
        assertEquals(SEEDED_RETRY_COUNT, ((Number) row.get("retry_count")).intValue(),
                "retry 预算不能被重置");
        assertEquals(SEEDED_LEASE_RECOVERY_COUNT, ((Number) row.get("lease_recovery_count")).intValue());
        assertEquals("PUBLISH_CONFIRM_TIMEOUT", row.get("last_error"));
        assertEquals(0, auditCount(operationId), "审计失败不得留下审计行");
    }

    // ------------------------------------------------------- 2) operation 幂等

    @Test
    void sameOperationIdAndReasonReturnsOriginalResultWithoutSecondAction() throws Exception {
        String eventId = newEventId();
        insertOutbox(eventId, 3, validPayload(eventId),
                SEEDED_RETRY_COUNT, SEEDED_LEASE_RECOVERY_COUNT, 10);
        String operationId = "op-" + UUID.randomUUID();
        String reason = "首次重放";

        VideoStatusOutboxReplayVO first = replayService.replay(eventId, operationId, reason, OPERATOR);
        assertTrue(first.isReplayApplied());
        assertEquals(1, auditCount(operationId));

        // 模拟事件再次失败：如果第二次请求真的执行动作，retry_count 会被再次归零。
        jdbc.update("UPDATE video_status_outbox SET status = 3, retry_count = 7 WHERE event_id = ?",
                eventId);

        VideoStatusOutboxReplayVO second = replayService.replay(eventId, operationId, reason, OPERATOR);
        assertTrue(second.isDuplicate());
        assertFalse(second.isReplayApplied());
        assertEquals(first.getOperationId(), second.getOperationId());
        assertNotNull(second.getCompletedAt(), "必须返回原审计记录的完成时间而不是当前时间");
        assertFalse(second.getCompletedAt().isAfter(LocalDateTime.now()),
                "原审计时间不能是未来的新时间");
        // before/after 来自持久化元数据：仍是历史操作，不被当前状态覆盖。
        assertEquals(SEEDED_RETRY_COUNT, second.getRetryCountBefore());
        assertEquals(0, second.getRetryCountAfter());
        assertEquals(SEEDED_LEASE_RECOVERY_COUNT, second.getLeaseRecoveryCountBefore());
        assertEquals(0, second.getLeaseRecoveryCountAfter());
        assertEquals("FAILED", second.getStatusNameBefore());
        assertEquals("PENDING", second.getStatusNameAfter());
        // 实时状态单独展示：已再次失败（status=3, retry=7）。
        assertEquals("FAILED", second.getCurrentStatusName());
        assertEquals(7, second.getCurrentRetryCount());
        assertEquals(7, ((Number) outboxRow(eventId).get("retry_count")).intValue(),
                "重复 operationId 不得执行第二次动作");
        assertEquals(3, ((Number) outboxRow(eventId).get("status")).intValue());
        assertEquals(1, auditCount(operationId), "不得重复写审计");

        // 同一 operationId 换 operator / 换 eventId 都属于“不同请求”，必须冲突而不是复用。
        BusinessException otherOperator = assertThrows(BusinessException.class,
                () -> replayService.replay(eventId, operationId, reason, "99"));
        assertEquals(409, otherOperator.getCode());
        assertEquals(1, auditCount(operationId));
    }

    @Test
    void sameOperationIdWithDifferentReasonIsRejectedWithoutMaskingOriginalResult() throws Exception {
        String eventId = newEventId();
        insertOutbox(eventId, 3, validPayload(eventId),
                SEEDED_RETRY_COUNT, SEEDED_LEASE_RECOVERY_COUNT, 10);
        String operationId = "op-" + UUID.randomUUID();
        String originalReason = "原始原因：根因已修复";

        VideoStatusOutboxReplayVO first =
                replayService.replay(eventId, operationId, originalReason, OPERATOR);
        assertTrue(first.isReplayApplied());
        Map<String, Object> metadataBefore = auditMetadata(operationId);

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> replayService.replay(eventId, operationId, "新原因：换个说法复用旧操作", OPERATOR));

        assertEquals(409, conflict.getCode());
        assertTrue(conflict.getMessage().contains("reason"), conflict.getMessage());
        assertEquals(1, auditCount(operationId), "reason 冲突不得新增审计");
        assertEquals(originalReason, auditRow(operationId).get("reason"),
                "原审计 reason 必须保持不变");
        assertEquals(metadataBefore, auditMetadata(operationId),
                "原审计 before/after 元数据必须保持不变");
        assertEquals(0, ((Number) outboxRow(eventId).get("status")).intValue(),
                "reason 冲突不得再动状态");
    }

    // ------------------------------------------------------------ 3) 安全窗口

    @Test
    void createdAtAtOrBeyondMaxAgeIsRejectedWithoutWritingAudit() {
        long maxAgeSeconds = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        for (long ageSeconds : List.of(maxAgeSeconds, maxAgeSeconds + 3600L)) {
            String eventId = newEventId();
            insertOutbox(eventId, 3, validPayload(eventId),
                    SEEDED_RETRY_COUNT, SEEDED_LEASE_RECOVERY_COUNT, ageSeconds);
            String operationId = "op-" + UUID.randomUUID();

            BusinessException rejected = assertThrows(BusinessException.class,
                    () -> replayService.replay(eventId, operationId, "超龄重放", OPERATOR));

            assertEquals(409, rejected.getCode());
            assertTrue(rejected.getMessage().contains("created_at")
                    || rejected.getMessage().contains("安全重放窗口"), rejected.getMessage());
            assertEquals(0, auditCount(operationId), "超龄拒绝不得写审计");
            assertEquals(3, ((Number) outboxRow(eventId).get("status")).intValue());
            assertEquals(SEEDED_RETRY_COUNT,
                    ((Number) outboxRow(eventId).get("retry_count")).intValue());
        }
    }

    @Test
    void occurredAtAtOrBeyondMaxAgeAndFutureOccurredAtAreRejected() throws Exception {
        long maxAgeSeconds = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        LocalDateTime now = LocalDateTime.now();

        // a) occurredAt 正好等于上限 → 拒绝（边界等于 max 也拒绝）
        String boundary = newEventId();
        insertOutbox(boundary, 3, validPayload(boundary, now.minusSeconds(maxAgeSeconds)),
                SEEDED_RETRY_COUNT, 0, 10);
        String opBoundary = "op-" + UUID.randomUUID();
        BusinessException boundaryError = assertThrows(BusinessException.class,
                () -> replayService.replay(boundary, opBoundary, "occurredAt 边界", OPERATOR));
        assertEquals(409, boundaryError.getCode());
        assertTrue(boundaryError.getMessage().contains("occurredAt"), boundaryError.getMessage());
        assertEquals(0, auditCount(opBoundary));
        assertEquals(3, ((Number) outboxRow(boundary).get("status")).intValue());

        // b) occurredAt 明显超龄（created_at 新鲜也不行）
        String stale = newEventId();
        insertOutbox(stale, 3, validPayload(stale, now.minusSeconds(maxAgeSeconds + 3600)),
                SEEDED_RETRY_COUNT, 0, 10);
        String opStale = "op-" + UUID.randomUUID();
        assertEquals(409, assertThrows(BusinessException.class,
                () -> replayService.replay(stale, opStale, "occurredAt 超龄", OPERATOR)).getCode());
        assertEquals(0, auditCount(opStale));

        // c) occurredAt 在未来（> now+5min）→ 拒绝，防止伪造新鲜度
        String future = newEventId();
        insertOutbox(future, 3, validPayload(future, now.plusMinutes(10)),
                SEEDED_RETRY_COUNT, 0, 10);
        String opFuture = "op-" + UUID.randomUUID();
        BusinessException futureError = assertThrows(BusinessException.class,
                () -> replayService.replay(future, opFuture, "occurredAt 未来", OPERATOR));
        assertEquals(422, futureError.getCode());
        assertTrue(futureError.getMessage().contains("未来"), futureError.getMessage());
        assertEquals(0, auditCount(opFuture));
        assertEquals(3, ((Number) outboxRow(future).get("status")).intValue());
    }

    // ---------------------------------------------------------- 4) 不安全行拒绝

    @Test
    void anyExistingConsumedRecordIsRejectedWithoutTouchingLedger() {
        for (int processStatus : new int[]{0, 1, 2, 3}) {
            String eventId = newEventId();
            insertOutbox(eventId, 3, validPayload(eventId),
                    SEEDED_RETRY_COUNT, SEEDED_LEASE_RECOVERY_COUNT, 10);
            insertConsumed(eventId, processStatus);
            Map<String, Object> consumedBefore = consumedRow(eventId);
            Map<String, Object> counterBefore = counterRow();
            String operationId = "op-" + UUID.randomUUID();

            BusinessException rejected = assertThrows(BusinessException.class,
                    () -> replayService.replay(eventId, operationId, "consumed 已存在", OPERATOR));

            assertEquals(409, rejected.getCode(), "consumed=" + processStatus + " 必须拒绝");
            if (processStatus == 2) {
                assertTrue(rejected.getMessage().contains("无需重投"), rejected.getMessage());
            } else if (processStatus == 3) {
                assertTrue(rejected.getMessage().contains("T10"), rejected.getMessage());
            } else {
                assertTrue(rejected.getMessage().contains("T09"), rejected.getMessage());
            }
            assertEquals(0, auditCount(operationId), "拒绝不得写审计");
            assertEquals(3, ((Number) outboxRow(eventId).get("status")).intValue(),
                    "consumed 拒绝不得改状态");
            assertEquals(SEEDED_RETRY_COUNT,
                    ((Number) outboxRow(eventId).get("retry_count")).intValue());
            assertEquals(consumedBefore, consumedRow(eventId), "不得修改消费账本");
            assertEquals(counterBefore, counterRow(), "不得修改 video_status 计数");
        }
    }

    @Test
    void unsafeRowsAreRejectedWithoutAudit() throws Exception {
        // a) payload 非法：解析失败 → 422
        String invalidPayloadEvent = newEventId();
        jdbc.update("INSERT INTO video_status_outbox("
                        + "event_id, aggregate_id, event_type, payload, status, retry_count, created_at) "
                        + "VALUES (?, ?, 'PLAY', '{}', 3, 1, NOW(3))",
                invalidPayloadEvent, vid);
        String opInvalidPayload = "op-" + UUID.randomUUID();
        BusinessException payloadError = assertThrows(BusinessException.class,
                () -> replayService.replay(invalidPayloadEvent, opInvalidPayload, "非法 payload", OPERATOR));
        assertEquals(422, payloadError.getCode());
        assertEquals(0, auditCount(opInvalidPayload));

        // b) eventId 与行不一致 → 422
        String mismatchEvent = newEventId();
        insertOutbox(mismatchEvent, 3,
                validPayload(newEventId()), SEEDED_RETRY_COUNT, 0, 10);
        String opMismatch = "op-" + UUID.randomUUID();
        assertEquals(422, assertThrows(BusinessException.class,
                () -> replayService.replay(mismatchEvent, opMismatch, "不一致", OPERATOR)).getCode());
        assertEquals(0, auditCount(opMismatch));

        // c) status = 2 (SENT) → 409 明确“已完成”
        String sentEvent = newEventId();
        insertOutbox(sentEvent, 2, validPayload(sentEvent), 0, 0, 10);
        String opSent = "op-" + UUID.randomUUID();
        BusinessException sentError = assertThrows(BusinessException.class,
                () -> replayService.replay(sentEvent, opSent, "已发送", OPERATOR));
        assertEquals(409, sentError.getCode());
        assertTrue(sentError.getMessage().contains("已完成投递"), sentError.getMessage());
        assertEquals(0, auditCount(opSent));

        // d) status = 0 / 1 → 409（仍由自动投递/租约恢复负责）
        for (int status : new int[]{0, 1}) {
            String inFlightEvent = newEventId();
            insertOutbox(inFlightEvent, status, validPayload(inFlightEvent), 0, 0, 1);
            String operationId = "op-" + UUID.randomUUID();
            assertEquals(409, assertThrows(BusinessException.class,
                    () -> replayService.replay(inFlightEvent, operationId, "在途", OPERATOR)).getCode());
            assertEquals(0, auditCount(operationId));
            assertEquals(status, ((Number) outboxRow(inFlightEvent).get("status")).intValue());
        }

        // 不存在的 eventId → 404
        assertEquals(404, assertThrows(BusinessException.class,
                () -> replayService.replay("missing-" + UUID.randomUUID(),
                        "op-" + UUID.randomUUID(), "缺失", OPERATOR)).getCode());
        // 缺少操作人 → 401（不能伪造/省略）
        String opNoOperatorEvent = newEventId();
        insertOutbox(opNoOperatorEvent, 3, validPayload(opNoOperatorEvent), 1, 0, 10);
        assertEquals(401, assertThrows(BusinessException.class,
                () -> replayService.replay(opNoOperatorEvent, "op-" + UUID.randomUUID(), "无操作人", null))
                .getCode());
    }

    // ------------------------------------------------------------- 5) GET 安全

    @Test
    void statusQueryReturnsSafeFieldsWithoutPayloadLeaseTokenOrRawLastError() throws Exception {
        String eventId = newEventId();
        insertOutbox(eventId, 3, validPayload(eventId),
                SEEDED_RETRY_COUNT, SEEDED_LEASE_RECOVERY_COUNT, 10);
        String secretError = "PUBLISH_JSON_SERIALIZE_FAILED payload={\"delta\":1} password=p@ss";
        jdbc.update("UPDATE video_status_outbox SET lease_token = 'lease-secret', last_error = ? "
                + "WHERE event_id = ?", secretError, eventId);
        insertConsumed(eventId, 1);

        VideoStatusOutboxStatusVO vo = replayService.getStatus(eventId);

        assertEquals(eventId, vo.getEventId());
        assertEquals(3, vo.getStatus());
        assertEquals("FAILED", vo.getStatusName());
        assertEquals(SEEDED_RETRY_COUNT, vo.getRetryCount());
        assertEquals(SEEDED_LEASE_RECOVERY_COUNT, vo.getLeaseRecoveryCount());
        assertEquals(1, vo.getConsumedProcessStatus());
        assertFalse(vo.isReplayEligible(), "consumed 已存在（即使 0/1）必须显示为不可重放");
        assertNotNull(vo.getReplayBlockedReason());
        assertTrue(vo.getReplayBlockedReason().contains("T09"), vo.getReplayBlockedReason());
        assertTrue(vo.getLastErrorPresent(), "存在失败详情必须只给布尔");
        assertEquals(VideoStatusOutboxReplayService.LAST_ERROR_HINT, vo.getLastErrorHint());
        assertFalse(vo.getLastErrorHint().contains("p@ss"));
        assertFalse(vo.getLastErrorHint().contains("delta"));
        assertFalse(vo.getNote().contains("lease-secret"));
        assertFalse(vo.getNote().contains("delta"));
        // VO 结构上就没有 last_error 原文字段，历史 raw 值不可能被序列化给 HTTP。
        for (var field : VideoStatusOutboxStatusVO.class.getDeclaredFields()) {
            assertFalse(field.getName().equalsIgnoreCase("lastError"),
                    "GET VO 不允许携带 last_error 原文");
        }

        // mapper 的安全查询根本不读取 payload / lease_token / last_error：不会把它们带进内存/日志。
        var safeRow = repairMapper.selectSafeByEventId(eventId);
        assertNotNull(safeRow);
        assertNull(safeRow.getPayload(), "safe query must not select payload");
        assertNull(safeRow.getLeaseToken(), "safe query must not select lease_token");
        assertNull(safeRow.getLastError(), "safe query must not select last_error raw text");
        assertTrue(repairMapper.selectLastErrorPresent(eventId));

        // 没有消费记录时 consumed 为空，状态仍按 FAILED 展示（是否可重放以 POST 校验为准）。
        String noConsumedEvent = newEventId();
        insertOutbox(noConsumedEvent, 3, validPayload(noConsumedEvent), 1, 0, 10);
        jdbc.update("UPDATE video_status_outbox SET last_error = NULL WHERE event_id = ?",
                noConsumedEvent);
        VideoStatusOutboxStatusVO plain = replayService.getStatus(noConsumedEvent);
        assertNull(plain.getConsumedProcessStatus());
        assertTrue(plain.isReplayEligible());
        assertFalse(plain.getLastErrorPresent());
        assertNull(plain.getLastErrorHint());

        System.out.printf(Locale.ROOT, "[T07-repair-IT] status query safe: vid=%d status=%s%n",
                vid, vo.getStatusName());
    }

    // ---------------------------------------------------------------- helpers

    private String newEventId() {
        return "t07r-" + UUID.randomUUID();
    }

    /** 与消费者解析路径一致的真实事件 JSON（含 schemaVersion=2 与精确 hotScoreDelta）。 */
    private String validPayload(String eventId) {
        return validPayload(eventId, LocalDateTime.now().minusSeconds(1));
    }

    private String validPayload(String eventId, LocalDateTime occurredAt) {
        long delta = 1L;
        VideoStatusChangedEvent event = new VideoStatusChangedEvent(
                eventId,
                vid,
                VideoStatusEventType.PLAY,
                delta,
                VideoStatusEventType.PLAY.calculateHotScore(delta),
                occurredAt,
                2,
                "t07r-it");
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void insertOutbox(String eventId,
                              int status,
                              String payload,
                              int retryCount,
                              int leaseRecoveryCount,
                              long ageSeconds) {
        jdbc.update("INSERT INTO video_status_outbox("
                        + "event_id, aggregate_id, event_type, payload, status, retry_count, "
                        + "lease_recovery_count, confirm_writeback_count, last_error, created_at) "
                        + "VALUES (?, ?, 'PLAY', ?, ?, ?, ?, ?, 'PUBLISH_CONFIRM_TIMEOUT', "
                        + "TIMESTAMPADD(SECOND, -?, NOW(3)))",
                eventId, vid, payload, status, retryCount, leaseRecoveryCount,
                SEEDED_CONFIRM_WRITEBACK_COUNT, ageSeconds);
    }

    private void insertConsumed(String eventId, int processStatus) {
        jdbc.update("INSERT INTO video_status_consumed_event("
                        + "event_id, vid, event_type, delta, payload, payload_hash, process_status) "
                        + "VALUES (?, ?, 'PLAY', 1, '{}', ?, ?)",
                eventId, vid, "0".repeat(64), processStatus);
    }

    private Map<String, Object> outboxRow(String eventId) {
        return jdbc.queryForMap("SELECT event_id, status, retry_count, lease_recovery_count, "
                + "confirm_writeback_count, next_retry_at, sending_at, lease_token, last_error, "
                + "payload, created_at "
                + "FROM video_status_outbox WHERE event_id = ?", eventId);
    }

    private Map<String, Object> consumedRow(String eventId) {
        return jdbc.queryForMap("SELECT event_id, vid, delta, payload, payload_hash, process_status, "
                + "consumer_retry_count, last_attempt_at, last_error, consumed_at, redis_applied_at, flushed_at "
                + "FROM video_status_consumed_event WHERE event_id = ?", eventId);
    }

    private int consumedCount(String eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_consumed_event WHERE event_id = ?",
                Integer.class, eventId);
        return count == null ? 0 : count;
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
        assertNotNull(lastError, "审计必须持久化 before/after 元数据到 last_error");
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
