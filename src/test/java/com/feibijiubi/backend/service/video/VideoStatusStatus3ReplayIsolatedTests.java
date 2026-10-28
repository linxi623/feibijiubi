package com.feibijiubi.backend.service.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.config.RedisScriptConfig;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.config.VideoStatusRabbitConfig;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusFailureAdminMapper;
import com.feibijiubi.backend.mq.VideoStatusConsumerRecoveryController;
import com.feibijiubi.backend.mq.VideoStatusEventConsumer;
import com.feibijiubi.backend.mq.VideoStatusEventPublisher;
import com.feibijiubi.backend.mq.VideoStatusMessageForwarder;
import com.feibijiubi.backend.mq.VideoStatusOutboxClaimService;
import com.feibijiubi.backend.mq.VideoStatusOutboxRelay;
import com.feibijiubi.backend.service.admin.VideoStatusFailureAdminService;
import com.feibijiubi.backend.service.admin.VideoStatusFailureAdminServiceImpl;
import com.feibijiubi.backend.service.admin.VideoStatusStatus3ReplayService;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusBatchFlushServiceImpl;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusConsumptionServiceImpl;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusEventFingerprintServiceImpl;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusRebuildServiceImpl;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusRebuildSnapshotServiceImpl;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusEventFingerprintService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.rabbitmq.RabbitConstants;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisHashOperations;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import com.feibijiubi.backend.vo.VideoStatusStatus3ReplayVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T10-B1 consumed status3 受控重放的真实隔离 IT：MySQL 13307 + Redis 16379 + RabbitMQ 15673。
 *
 * <p>只在显式 JVM 参数 {@code -DvideoStatusIT=true} 时运行，复用仓库已有的
 * {@link VideoStatusIsolatedIntegrationTests.IsolationGuard} 与
 * {@code video-status-isolated.properties}，不启动 application.yml，不连其它环境。
 * 每个用例只新建自己的 user/video/video_status/outbox/consumed 行，不删除历史数据、
 * 不清库；用例开始前只清理本链路已知队列。</p>
 *
 * <h2>覆盖的不变量</h2>
 * <ol>
 *   <li>proven 路径（processed 证据仍在）：consumed 3→0、outbox SENT→PENDING、COMPLETED 审计
 *       在同一真实事务提交；随后由<b>真实</b> Outbox relay 投递、真实消费者判 DUPLICATE、
 *       真实批量落库推进到 FLUSHED，且计数只累计一次；</li>
 *   <li>processed 缺失 / 剩余 TTL 不足 / 刷库型 status3（redis_applied_at 非空）/ 超龄 /
 *       Outbox 非 SENT / 指纹不一致全部拒绝，且目标行、Outbox、审计、计数零变化；</li>
 *   <li>并发同 operationId 只产生一条 COMPLETED 审计且只应用一次；</li>
 *   <li>审计写入失败时真实 MySQL 事务整体回滚（consumed 仍 status3、outbox 仍 SENT、无审计行）——
 *       这是 Mockito 无法证明的部分；</li>
 *   <li>重放预算耗尽后拒绝，避免“重放→再入 status3→再重放”死循环；</li>
 *   <li>预检查与重放共用同一判定：拒绝码一致，且预检查不写任何状态。</li>
 * </ol>
 *
 * <p>运行：{@code ./mvnw -DvideoStatusIT=true -Dtest=VideoStatusStatus3ReplayIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusStatus3ReplayIsolatedTests.TestApplication.class,
        properties = {
                "spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none",
                // 本类只按显式调用推进 relay/flush，避免后台调度与本类断言竞争。
                "app.video-status.scheduling-enabled=false"
        })
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VideoStatusStatus3ReplayIsolatedTests {

    private static final String SOURCE = VideoStatusFailureAdminService.SOURCE_CONSUMED;
    private static final String OPERATOR = "42";
    private static final String REASON = "T10-B1 受控重放：processed 证据仍在";

    @Configuration(proxyBeanMethods = false)
    @Profile("video-status-isolated")
    @EnableAutoConfiguration(excludeName =
            "org.redisson.spring.starter.RedissonAutoConfigurationV2")
    @EnableConfigurationProperties(VideoStatusProperties.class)
    @MapperScan("com.feibijiubi.backend.mapper")
    @EnableRabbit
    @Import({RedisScriptConfig.class, VideoStatusRabbitConfig.class,
            VideoStatusServiceImpl.class, VideoStatusConsumptionServiceImpl.class,
            VideoStatusRebuildServiceImpl.class, VideoStatusRebuildSnapshotServiceImpl.class,
            VideoStatusBatchFlushServiceImpl.class, VideoStatusVidMutex.class,
            VideoStatusEventFingerprintServiceImpl.class, VideoStatusEventConsumer.class,
            VideoStatusConsumerRecoveryController.class,
            VideoStatusMessageForwarder.class, VideoStatusEventPublisher.class,
            VideoStatusOutboxClaimService.class, VideoStatusOutboxRelay.class,
            VideoStatusFailureAdminServiceImpl.class, VideoStatusStatus3ReplayService.class,
            RedisUtils.class, JsonUtils.class,
            RedisHashOperations.class, RedisSetOperations.class})
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
    @Autowired VideoStatusConsumedEventMapper consumedEventMapper;
    @Autowired VideoStatusFailureAdminMapper failureAdminMapper;
    @Autowired VideoStatusEventFingerprintService fingerprint;
    @Autowired VideoStatusService stats;
    @Autowired VideoStatusRebuildService rebuild;
    @Autowired VideoStatusBatchFlushService flushService;
    @Autowired VideoStatusVidMutex vidMutex;
    @Autowired VideoStatusOutboxRelay relay;
    @Autowired VideoStatusStatus3ReplayService replayService;
    @Autowired VideoStatusFailureAdminService failureAdminService;
    @Autowired VideoStatusProperties properties;
    @Autowired ObjectMapper json;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired PlatformTransactionManager transactionManager;

    int vid;

    @BeforeEach
    void seedOwnVideo() {
        String username = "t10b1-it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) "
                + "VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject(
                "SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 't10b1-isolated', 'test', 'test', '', '', '', '')", uid);
        vid = jdbc.queryForObject(
                "SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times) VALUES (?,0)", vid);
        rebuild.ensureInitialized(vid);
        resetKnownQueues();
    }

    // ------------------------------------------------------------------ proven path

    @Test
    void provenStatus3ReplayConvergesExactlyOnceThroughRealRelay() throws Exception {
        VideoStatusChangedEvent event = seedStatus3(120, 2);
        // 模拟“Redis 已应用、markRedisApplied 回写丢失后 RECEIVED 超龄转 status3”：
        // processed 证据仍在，因此重复投递必须被判 DUPLICATE。
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        assertEquals(1L, currentPlayTimes());
        String processedKey = RedisKeyUtils.processedKey(event.eventId());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(processedKey)));

        Map<String, Object> consumedBefore = consumedRow(event.eventId());
        Map<String, Object> outboxBefore = outboxRow(event.eventId());
        String operationId = "op-" + UUID.randomUUID();

        VideoStatusStatus3ReplayVO vo =
                replayService.replay(SOURCE, event.eventId(), operationId, REASON, OPERATOR);

        assertTrue(vo.isReplayApplied());
        assertFalse(vo.isDuplicate());
        assertEquals("OUTBOX_PENDING_RELAY", vo.getDeliveryMode());
        assertEquals(3, vo.getProcessStatusBefore());
        assertEquals(0, vo.getProcessStatusAfter());
        assertEquals("SENT", vo.getOutboxStatusNameBefore());
        assertEquals("PENDING", vo.getOutboxStatusNameAfter());
        assertEquals(1, vo.getReplayAttemptNo());
        assertNotNull(vo.getProcessedTtlSecondsAtReplay());
        assertTrue(vo.getProcessedTtlSecondsAtReplay()
                        >= properties.getStatus3ReplayRequiredProcessedTtlSeconds(),
                "只有 processed 剩余 TTL 覆盖重试窗口才允许重放");

        // 真实 MySQL 行：3→0，Outbox 2→0，且历史证据未被抹掉。
        Map<String, Object> consumedAfter = consumedRow(event.eventId());
        assertEquals(0, intOf(consumedAfter.get("process_status")));
        assertEquals(intOf(consumedBefore.get("consumer_retry_count")),
                intOf(consumedAfter.get("consumer_retry_count")),
                "重放不得清零历史 consumer_retry_count");
        assertEquals(consumedBefore.get("consumed_at"), consumedAfter.get("consumed_at"),
                "重放不得刷新事件年龄");
        assertEquals(consumedBefore.get("payload_hash"), consumedAfter.get("payload_hash"));
        assertEquals(consumedBefore.get("last_error"), consumedAfter.get("last_error"),
                "失败详情是证据，重放不清空 last_error");

        Map<String, Object> outboxAfterReplay = outboxRow(event.eventId());
        assertEquals(0, intOf(outboxAfterReplay.get("status")));
        assertNull(outboxAfterReplay.get("next_retry_at"));
        assertNull(outboxAfterReplay.get("lease_token"));
        assertNull(outboxAfterReplay.get("sending_at"));
        assertEquals(intOf(outboxBefore.get("retry_count")),
                intOf(outboxAfterReplay.get("retry_count")),
                "重放不得清零 Outbox 历史发布预算");

        Map<String, Object> audit = auditRow(operationId);
        assertEquals(1, intOf(audit.get("operation_status")));
        assertEquals(VideoStatusStatus3ReplayService.ACTION_REPLAY_TO_RECEIVED,
                audit.get("action"));
        assertNotNull(audit.get("completed_at"));

        // 真实 relay + 真实消费者 + 真实刷库：DUPLICATE 不重复累计，最终 FLUSHED。
        convergeToFlushedExactlyOnce(event.eventId(), 1L);
        assertEquals(1L, currentPlayTimes(), "processed 仍在时重投必须 DUPLICATE");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT play_times FROM video_status WHERE vid=?", Long.class, vid),
                "DB 只能累计一次");
        assertEquals(2, intOf(outboxRow(event.eventId()).get("status")),
                "relay 成功投递后 Outbox 必须回到 SENT");
    }

    // --------------------------------------------------------------------- refusals

    @Test
    void missingProcessedEvidenceIsRefusedAndNothingIsWritten() {
        VideoStatusChangedEvent event = seedStatus3(120, 2);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        redis.delete(RedisKeyUtils.processedKey(event.eventId()));
        Map<String, Object> consumedBefore = consumedRow(event.eventId());
        Map<String, Object> outboxBefore = outboxRow(event.eventId());
        String operationId = "op-" + UUID.randomUUID();

        RuntimeException error = assertThrows(RuntimeException.class, () -> replayService.replay(
                SOURCE, event.eventId(), operationId, REASON, OPERATOR));

        assertTrue(error.getMessage().contains(
                        VideoStatusStatus3ReplayService.REFUSAL_PROCESSED_EVIDENCE_MISSING),
                error.getMessage());
        assertEquals(consumedBefore, consumedRow(event.eventId()),
                "拒绝时必须保持 consumed 行不变");
        assertEquals(outboxBefore, outboxRow(event.eventId()),
                "拒绝时必须保持 Outbox 行不变");
        assertEquals(0, auditCount(operationId), "拒绝不得写审计");
        assertEquals(1L, currentPlayTimes());
    }

    @Test
    void insufficientProcessedTtlIsRefused() {
        VideoStatusChangedEvent event = seedStatus3(120, 2);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        long required = properties.getStatus3ReplayRequiredProcessedTtlSeconds();
        redis.opsForValue().set(RedisKeyUtils.processedKey(event.eventId()), "1",
                required / 2, TimeUnit.SECONDS);
        Map<String, Object> consumedBefore = consumedRow(event.eventId());
        String operationId = "op-" + UUID.randomUUID();

        RuntimeException error = assertThrows(RuntimeException.class, () -> replayService.replay(
                SOURCE, event.eventId(), operationId, REASON, OPERATOR));

        assertTrue(error.getMessage().contains(
                        VideoStatusStatus3ReplayService.REFUSAL_PROCESSED_TTL_INSUFFICIENT),
                error.getMessage());
        assertEquals(consumedBefore, consumedRow(event.eventId()));
        assertEquals(0, auditCount(operationId));
    }

    @Test
    void flushOriginStatus3IsRefusedBecauseRedisIsAlreadyApplied() {
        VideoStatusChangedEvent event = seedStatus3(120, 2);
        // 刷库型 status3：markFlushRepairRequired 把 1→3，redis_applied_at 非空。
        jdbc.update("UPDATE video_status_consumed_event "
                + "SET redis_applied_at = NOW(3) WHERE event_id = ?", event.eventId());
        Map<String, Object> consumedBefore = consumedRow(event.eventId());
        Map<String, Object> outboxBefore = outboxRow(event.eventId());
        String operationId = "op-" + UUID.randomUUID();

        RuntimeException error = assertThrows(RuntimeException.class, () -> replayService.replay(
                SOURCE, event.eventId(), operationId, REASON, OPERATOR));

        assertTrue(error.getMessage().contains(
                        VideoStatusStatus3ReplayService.REFUSAL_FLUSH_ORIGIN_NOT_REPLAYABLE),
                error.getMessage());
        assertEquals(consumedBefore, consumedRow(event.eventId()));
        assertEquals(outboxBefore, outboxRow(event.eventId()));
        assertEquals(0, auditCount(operationId));
    }

    @Test
    void payloadIdentityMismatchIsRefused() {
        VideoStatusChangedEvent event = seedStatus3(120, 2);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        jdbc.update("UPDATE video_status_consumed_event SET payload_hash = ? WHERE event_id = ?",
                "0".repeat(64), event.eventId());
        Map<String, Object> consumedBefore = consumedRow(event.eventId());
        String operationId = "op-" + UUID.randomUUID();

        RuntimeException error = assertThrows(RuntimeException.class, () -> replayService.replay(
                SOURCE, event.eventId(), operationId, REASON, OPERATOR));

        assertTrue(error.getMessage().contains(
                        VideoStatusStatus3ReplayService.REFUSAL_FINGERPRINT_MISMATCH),
                error.getMessage());
        assertEquals(consumedBefore, consumedRow(event.eventId()));
        assertEquals(0, auditCount(operationId));
    }

    @Test
    void overSafeWindowIsRefused() {
        long maxAge = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        VideoStatusChangedEvent event = seedStatus3(maxAge + 30, 2);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        Map<String, Object> consumedBefore = consumedRow(event.eventId());
        String operationId = "op-" + UUID.randomUUID();

        RuntimeException error = assertThrows(RuntimeException.class, () -> replayService.replay(
                SOURCE, event.eventId(), operationId, REASON, OPERATOR));

        assertTrue(error.getMessage().contains(
                        VideoStatusStatus3ReplayService.REFUSAL_OVER_SAFE_WINDOW),
                error.getMessage());
        assertEquals(consumedBefore, consumedRow(event.eventId()));
        assertEquals(0, auditCount(operationId));
    }

    @Test
    void outboxNotSentIsRefusedInsteadOfFallingBackToDirectSend() {
        VideoStatusChangedEvent event = seedStatus3(120, 3);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        Map<String, Object> consumedBefore = consumedRow(event.eventId());
        String operationId = "op-" + UUID.randomUUID();

        RuntimeException error = assertThrows(RuntimeException.class, () -> replayService.replay(
                SOURCE, event.eventId(), operationId, REASON, OPERATOR));

        assertTrue(error.getMessage().contains(
                        VideoStatusStatus3ReplayService.REFUSAL_OUTBOX_NOT_SENT),
                error.getMessage());
        assertEquals(consumedBefore, consumedRow(event.eventId()));
        assertEquals(3, intOf(outboxRow(event.eventId()).get("status")));
        assertEquals(0, auditCount(operationId));
    }

    // --------------------------------------------------------- idempotency / budget

    @Test
    void concurrentSameOperationIdAppliesExactlyOnce() throws Exception {
        VideoStatusChangedEvent event = seedStatus3(120, 2);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        String operationId = "op-" + UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = List.of(
                    pool.submit(() -> replayConcurrently(
                            ready, start, event.eventId(), operationId)),
                    pool.submit(() -> replayConcurrently(
                            ready, start, event.eventId(), operationId)));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "callers did not become ready");
            start.countDown();

            int applied = 0;
            int duplicate = 0;
            int conflicts = 0;
            for (Future<Object> future : futures) {
                Object outcome = future.get(20, TimeUnit.SECONDS);
                if (outcome instanceof VideoStatusStatus3ReplayVO result) {
                    if (result.isReplayApplied()) {
                        applied++;
                    }
                    if (result.isDuplicate()) {
                        duplicate++;
                    }
                } else if (outcome instanceof RuntimeException error) {
                    conflicts++;
                } else {
                    throw new AssertionError("unexpected concurrent outcome: " + outcome);
                }
            }
            assertEquals(1, applied, "只有一次请求可以真正应用重放");
            assertEquals(1, duplicate + conflicts);
            assertEquals(1, auditCount(operationId), "只能有一条 COMPLETED 审计");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(0, intOf(consumedRow(event.eventId()).get("process_status")));
        assertEquals(0, intOf(outboxRow(event.eventId()).get("status")));
    }

    @Test
    void replayBudgetExhaustedIsRefused() {
        VideoStatusChangedEvent event = seedStatus3(120, 2);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        int maxAttempts = properties.getStatus3ReplayMaxAttempts();
        for (int index = 0; index < maxAttempts; index++) {
            jdbc.update("INSERT INTO video_status_consumption_repair_log("
                            + "operation_id, event_id, action, operation_status, reason, operator, "
                            + "created_at, completed_at) VALUES (?, ?, ?, 1, 'prior', '1', "
                            + "NOW(3), NOW(3))",
                    "prior-" + UUID.randomUUID(), event.eventId(),
                    VideoStatusStatus3ReplayService.ACTION_REPLAY_TO_RECEIVED);
        }
        String operationId = "op-" + UUID.randomUUID();

        RuntimeException error = assertThrows(RuntimeException.class, () -> replayService.replay(
                SOURCE, event.eventId(), operationId, REASON, OPERATOR));

        assertTrue(error.getMessage().contains(
                        VideoStatusStatus3ReplayService.REFUSAL_REPLAY_BUDGET_EXHAUSTED),
                error.getMessage());
        assertEquals(0, auditCount(operationId));
        assertEquals(3, intOf(consumedRow(event.eventId()).get("process_status")));
    }

    // ------------------------------------------------------- real transaction rollback

    @Test
    void auditInsertFailureRollsBackTheWholeRealTransaction() {
        VideoStatusChangedEvent event = seedStatus3(120, 2);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        Map<String, Object> consumedBefore = consumedRow(event.eventId());
        Map<String, Object> outboxBefore = outboxRow(event.eventId());
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
                                return method.invoke(failureAdminMapper, args);
                            } catch (InvocationTargetException e) {
                                throw e.getTargetException();
                            }
                        });
        VideoStatusStatus3ReplayService failingService = new VideoStatusStatus3ReplayService(
                failingMapper, properties, json, fingerprint, redis);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThrows(DataIntegrityViolationException.class,
                () -> template.executeWithoutResult(status -> failingService.replay(
                        SOURCE, event.eventId(), operationId, REASON, OPERATOR)));

        assertEquals(consumedBefore, consumedRow(event.eventId()),
                "审计失败必须回滚 consumed 的 3→0");
        assertEquals(outboxBefore, outboxRow(event.eventId()),
                "审计失败必须回滚 Outbox 的 SENT→PENDING");
        assertEquals(0, auditCount(operationId), "失败事务不得留下审计行");

        // operationId 未被占用：修正后可正常重放。
        VideoStatusStatus3ReplayVO retried = replayService.replay(
                SOURCE, event.eventId(), operationId, REASON, OPERATOR);
        assertTrue(retried.isReplayApplied());
        assertEquals(1, auditCount(operationId));
        assertEquals(0, intOf(consumedRow(event.eventId()).get("process_status")));
    }

    // ------------------------------------------------------------------- prechecks

    @Test
    void precheckMirrorsReplayJudgementWithoutWritingAnything() {
        VideoStatusChangedEvent event = seedStatus3(120, 2);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        Map<String, Object> consumedBefore = consumedRow(event.eventId());
        Map<String, Object> outboxBefore = outboxRow(event.eventId());

        VideoStatusFailureAdminService.ReplayPrecheck eligible =
                failureAdminService.precheck(SOURCE, event.eventId()).replay();
        assertNotNull(eligible);
        assertEquals(VideoStatusStatus3ReplayService.DECISION_PROVEN_REPLAY_ELIGIBLE,
                eligible.decision());
        assertTrue(eligible.eligible());
        assertFalse(eligible.redisAppliedEvidencePresent(),
                "processed 证据不能冒充 redis_applied_at 刷库证据");
        assertNull(eligible.refusalCode());
        assertEquals(0, eligible.replayAttempts());
        assertEquals(properties.getStatus3ReplayMaxAttempts(), eligible.maxReplayAttempts());
        assertEquals(2, eligible.outboxStatus());
        assertEquals(consumedBefore, consumedRow(event.eventId()),
                "预检查不得修改 consumed 行");
        assertEquals(outboxBefore, outboxRow(event.eventId()),
                "预检查不得修改 Outbox 行");

        redis.delete(RedisKeyUtils.processedKey(event.eventId()));
        VideoStatusFailureAdminService.ReplayPrecheck refused =
                failureAdminService.precheck(SOURCE, event.eventId()).replay();
        assertNotNull(refused);
        assertEquals(VideoStatusStatus3ReplayService.DECISION_REFUSED, refused.decision());
        assertFalse(refused.eligible());
        assertEquals(VideoStatusStatus3ReplayService.REFUSAL_PROCESSED_EVIDENCE_MISSING,
                refused.refusalCode());
        assertEquals(consumedBefore, consumedRow(event.eventId()));
    }

    // ---------------------------------------------------------------------- helpers

    private Object replayConcurrently(CountDownLatch ready, CountDownLatch start,
                                      String eventId, String operationId)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent start timed out");
        }
        try {
            return replayService.replay(SOURCE, eventId, operationId, REASON, OPERATOR);
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** 插入一条 status3 的消费账本 + 同 eventId 的 Outbox 行（状态由调用方指定）。 */
    private VideoStatusChangedEvent seedStatus3(long ageSeconds, int outboxStatus) {
        VideoStatusChangedEvent event = new VideoStatusChangedEvent(
                "t10b1-it-" + UUID.randomUUID(), vid, VideoStatusEventType.PLAY,
                1L, 1D, LocalDateTime.now(), 2, null);
        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("INSERT INTO video_status_outbox("
                        + "event_id, aggregate_id, event_type, payload, status, retry_count, "
                        + "lease_recovery_count, confirm_writeback_count, created_at, sent_at) "
                        + "VALUES (?, ?, ?, ?, ?, 2, 1, 3, "
                        + "DATE_SUB(NOW(3), INTERVAL 900 SECOND), "
                        + "CASE WHEN ? = 2 THEN NOW(3) ELSE NULL END)",
                event.eventId(), vid, event.type().name(), payload, outboxStatus, outboxStatus);
        jdbc.update("INSERT INTO video_status_consumed_event("
                        + "event_id, vid, event_type, delta, payload, payload_hash, "
                        + "process_status, consumer_retry_count, consumed_at, last_attempt_at, "
                        + "last_error) VALUES (?, ?, ?, ?, ?, ?, 3, 2, "
                        + "DATE_SUB(NOW(3), INTERVAL ? SECOND), "
                        + "DATE_SUB(NOW(3), INTERVAL ? SECOND), ?)",
                event.eventId(), vid, event.type().name(), event.delta(), payload,
                fingerprint.hash(event), ageSeconds, ageSeconds,
                "RECEIVED 事件超过自动重放安全年龄，禁止继续应用 Redis，eventId="
                        + event.eventId());
        return event;
    }

    private Map<String, Object> consumedRow(String eventId) {
        return new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT event_id, vid, event_type, delta, payload, payload_hash, process_status, "
                        + "consumer_retry_count, last_attempt_at, last_error, consumed_at, "
                        + "redis_applied_at, flushed_at "
                        + "FROM video_status_consumed_event WHERE event_id = ?", eventId));
    }

    private Map<String, Object> outboxRow(String eventId) {
        return new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT event_id, aggregate_id, event_type, payload, status, retry_count, "
                        + "lease_recovery_count, confirm_writeback_count, next_retry_at, "
                        + "sending_at, lease_token, last_error, created_at, sent_at "
                        + "FROM video_status_outbox WHERE event_id = ?", eventId));
    }

    private Map<String, Object> auditRow(String operationId) {
        return jdbc.queryForMap("SELECT operation_id, event_id, action, operation_status, reason, "
                + "operator, last_error, created_at, completed_at "
                + "FROM video_status_consumption_repair_log WHERE operation_id = ?", operationId);
    }

    private int auditCount(String operationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_consumption_repair_log WHERE operation_id = ?",
                Integer.class, operationId);
        return count == null ? 0 : count;
    }

    private static int intOf(Object value) {
        return ((Number) value).intValue();
    }

    private long currentPlayTimes() {
        Object value = redis.opsForHash().get(
                RedisKeyUtils.videoStatus(vid), "playTimes");
        assertNotNull(value, "前置条件：current 必须已初始化");
        return Long.parseLong(String.valueOf(value));
    }

    /**
     * 真实 relay 投递 + 真实消费者判 DUPLICATE + 真实刷库：直到事件 FLUSHED。
     * relay 会顺带处理库里其余的 PENDING 行，这是允许的；本方法只断言本用例的事件。
     */
    private void convergeToFlushedExactlyOnce(String eventId, long expectedCount)
            throws Exception {
        await(() -> {
            relay.relay();
            VideoStatusConsumedEvent row = consumedEventMapper.selectByEventId(eventId);
            if (row != null && row.getProcessStatus() == 1) {
                vidMutex.withLock(vid, () -> flushService.flushOneVideo(vid, 100));
            }
            VideoStatusConsumedEvent current = consumedEventMapper.selectByEventId(eventId);
            return current != null && current.getProcessStatus() == 2;
        });
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_consumed_event WHERE event_id=?",
                Integer.class, eventId), "同一 eventId 不得新增第二行");
        assertEquals(expectedCount, currentPlayTimes());
    }

    private void resetKnownQueues() {
        listenerRegistry.stop();
        RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
        for (String queue : List.of(RabbitConstants.MAIN_QUEUE,
                RabbitConstants.RETRY_QUEUE,
                RabbitConstants.RETRY_QUEUE_30S,
                RabbitConstants.RETRY_QUEUE_120S,
                RabbitConstants.DEAD_QUEUE)) {
            if (admin.getQueueProperties(queue) != null) {
                admin.purgeQueue(queue, false);
            }
        }
        listenerRegistry.start();
    }

    private void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(), "condition did not converge in 60 seconds");
    }
}
