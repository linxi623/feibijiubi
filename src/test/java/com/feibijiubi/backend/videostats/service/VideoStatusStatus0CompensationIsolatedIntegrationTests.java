package com.feibijiubi.backend.videostats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.config.RedisScriptConfig;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.config.VideoStatusRabbitConfig;
import com.feibijiubi.backend.videostats.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.videostats.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.videostats.mq.VideoStatusConsumerRecoveryController;
import com.feibijiubi.backend.videostats.mq.VideoStatusEventConsumer;
import com.feibijiubi.backend.videostats.mq.VideoStatusEventPublisher;
import com.feibijiubi.backend.videostats.mq.VideoStatusMessageForwarder;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusBatchFlushServiceImpl;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusConsumptionServiceImpl;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusEventFingerprintServiceImpl;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusRebuildServiceImpl;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusRebuildSnapshotServiceImpl;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusServiceImpl;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusStatus0CompensationClaimService;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusStatus0CompensationServiceImpl;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.videostats.mq.RabbitConstants;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisHashOperations;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * T09 status0 补偿的真实隔离 IT：MySQL 13307 + Redis 16379 + RabbitMQ 15673。
 *
 * <p>只在显式 JVM 参数 {@code -DvideoStatusIT=true} 时运行，复用仓库已有的
 * {@link VideoStatusIsolatedIntegrationTests.IsolationGuard} 与
 * {@code video-status-isolated.properties}，不启动 application.yml，不连其它环境。
 * 每个用例只新建自己的 user/video/video_status/consumed_event 行，不删除历史数据、
 * 不清理全库；队列在用例开始前只清理本链路已知队列，避免上次中断的残留消息造成串扰。</p>
 *
 * <p>覆盖：已应用但未 mark 的原 eventId 可安全收敛一次；登记后未应用在三闸门受控模式
 * 收敛一次；默认/事故闸门下 processed 单丢拒发且不累计；过期租约被接管后旧 token 全部
 * CAS 失败；超龄事件不重投；publish 成功但回写失败后同一 eventId 可再次收敛。</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusStatus0CompensationIsolatedIntegrationTests.TestApplication.class,
        properties = {
                "spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none",
                // 本类只按显式调用推进落库，避免后台调度与本类断言竞争。
                "app.video-status.scheduling-enabled=false"
        })
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VideoStatusStatus0CompensationIsolatedIntegrationTests {

    @Configuration(proxyBeanMethods = false)
    @Profile("video-status-isolated")
    @EnableAutoConfiguration(excludeName =
            "org.redisson.spring.starter.RedissonAutoConfigurationV2")
    @EnableConfigurationProperties(VideoStatusProperties.class)
    @MapperScan("com.feibijiubi.backend.videostats.mapper")
    @EnableRabbit
    @Import({RedisScriptConfig.class, VideoStatusRabbitConfig.class,
            VideoStatusServiceImpl.class, VideoStatusConsumptionServiceImpl.class,
            VideoStatusRebuildServiceImpl.class, VideoStatusRebuildSnapshotServiceImpl.class,
            VideoStatusBatchFlushServiceImpl.class, VideoStatusVidMutex.class,
            VideoStatusEventFingerprintServiceImpl.class, VideoStatusEventConsumer.class,
            VideoStatusConsumerRecoveryController.class,
            VideoStatusMessageForwarder.class, VideoStatusEventPublisher.class,
            VideoStatusStatus0CompensationClaimService.class,
            VideoStatusStatus0CompensationServiceImpl.class,
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
    @Autowired VideoStatusConsumptionService consumption;
    @Autowired VideoStatusEventFingerprintService fingerprint;
    @Autowired VideoStatusService stats;
    @Autowired VideoStatusRebuildService rebuild;
    @Autowired VideoStatusStatus0CompensationService compensation;
    @Autowired VideoStatusBatchFlushService flushService;
    @Autowired VideoStatusVidMutex vidMutex;
    @Autowired VideoStatusProperties properties;
    @Autowired ObjectMapper json;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry listenerRegistry;
    @MockitoSpyBean VideoStatusEventPublisher publisher;

    int vid;

    @BeforeEach
    void seedOwnVideoAndResetGates() {
        properties.setStatus0CompensationEnabled(false);
        properties.setStatus0CompensationIntegrityConfirmed(false);
        properties.setStatus0CompensationAllowUnprovenReplay(false);
        vid = freshVideo();
        rebuild.ensureInitialized(vid);
        resetKnownQueues();
    }

    @AfterEach
    void restoreDefaultGates() {
        properties.setStatus0CompensationEnabled(false);
        properties.setStatus0CompensationIntegrityConfirmed(false);
        properties.setStatus0CompensationAllowUnprovenReplay(false);
    }

    @Test
    void appliedButUnmarkedOriginalEventIdConvergesExactlyOnce() throws Exception {
        properties.setStatus0CompensationEnabled(true);
        properties.setStatus0CompensationIntegrityConfirmed(true);
        var event = registerOldEvent(120);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        assertEquals(1, current(), "前置条件：Redis 已应用但消费行仍未 mark");
        assertEquals(0, consumedRow(event.eventId()).getProcessStatus());

        clearInvocations(publisher);
        var result = compensation.runOnce(consumedRow(event.eventId()).getId() - 1);

        assertEquals(1, result.republished());
        assertEquals(0, result.unprovenRepublished());
        verify(publisher, times(1)).publish(
                argThat(e -> event.eventId().equals(e.eventId())), anyString());
        convergeToFlushedExactlyOnce(event.eventId(), 1L);
        assertEquals(1L, current(), "processed 仍在时重投必须是 DUPLICATE，不得再次累计");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT play_times FROM video_status WHERE vid=?", Long.class, vid));
    }

    @Test
    void registeredButNeverAppliedConvergesOnceUnderAllThreeGates() throws Exception {
        var event = registerOldEvent(120);
        assertEquals(0, current(), "前置条件：登记后未应用");
        assertFalse(Boolean.TRUE.equals(
                redis.hasKey(RedisKeyUtils.processedKey(event.eventId()))));
        properties.setStatus0CompensationEnabled(true);
        properties.setStatus0CompensationIntegrityConfirmed(true);
        properties.setStatus0CompensationAllowUnprovenReplay(true);

        clearInvocations(publisher);
        var result = compensation.runOnce(consumedRow(event.eventId()).getId() - 1);

        assertEquals(1, result.republished());
        assertEquals(1, result.unprovenRepublished(),
                "三闸门受控模式必须单独报告 unproven 重放");
        verify(publisher, times(1)).publish(
                argThat(e -> event.eventId().equals(e.eventId())), anyString());
        convergeToFlushedExactlyOnce(event.eventId(), 1L);
        assertEquals(1L, current(), "受控重放只允许累计一次");
    }

    @Test
    void missingProcessedOnlyIsRefusedByDefaultAndIncidentGatesWithoutAccumulation()
            throws Exception {
        var event = registerOldEvent(120);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        assertEquals(1, current());
        redis.delete(RedisKeyUtils.processedKey(event.eventId()));
        setDbAge(event.eventId(), 120);
        long id = consumedRow(event.eventId()).getId();

        clearInvocations(publisher);
        var disabled = compensation.runOnce(id - 1);
        assertEquals(0, disabled.claimed(), "默认总开关关闭时不得领取");
        assertEquals(0, disabled.republished());
        verify(publisher, never()).publish(
                argThat(e -> event.eventId().equals(e.eventId())), anyString());
        assertEquals(1L, current(), "默认闸门下不得累计");
        assertEquals(0, consumedRow(event.eventId()).getProcessStatus());

        // 事故模式：enabled + integrity 打开，但 allow-unproven-replay 保持关闭。
        properties.setStatus0CompensationEnabled(true);
        properties.setStatus0CompensationIntegrityConfirmed(true);
        properties.setStatus0CompensationAllowUnprovenReplay(false);
        clearInvocations(publisher);
        var incident = compensation.runOnce(id - 1);

        assertEquals(1, incident.claimed());
        assertEquals(0, incident.republished());
        assertEquals(1, incident.skippedNoProcessedEvidence());
        verify(publisher, never()).publish(
                argThat(e -> event.eventId().equals(e.eventId())), anyString());
        VideoStatusConsumedEvent row = consumedRow(event.eventId());
        assertEquals(0, row.getProcessStatus(), "证据不足只能保留 RECEIVED，不得转 status3");
        assertEquals("STATUS0_PROCESSED_EVIDENCE_MISSING", row.getLastError());
        assertNotNull(row.getCompensationNextAttemptAt());
        assertEquals(1L, current(), "processed 单丢时拒发，current 不得再累计");
    }

    @Test
    void expiredLeaseCanBeTakenOverButOldTokenCasAlwaysFails() {
        var event = registerOldEvent(120);
        jdbc.update("UPDATE video_status_consumed_event "
                        + "SET compensation_lease_token='old-owner', "
                        + "compensation_lease_until=DATE_SUB(NOW(3), INTERVAL 5 SECOND), "
                        + "compensation_attempts=1 "
                        + "WHERE event_id=?",
                event.eventId());

        assertEquals(1, consumedEventMapper.claimStatus0Compensation(
                event.eventId(), "new-owner", 30, 60L, 604_800L, 5),
                "过期租约必须允许新的 owner 接管");
        VideoStatusConsumedEvent claimed = consumedRow(event.eventId());
        assertEquals("new-owner", claimed.getCompensationLeaseToken());
        assertEquals(2, claimed.getCompensationAttempts());

        assertEquals(0, consumedEventMapper.releaseStatus0Compensation(
                event.eventId(), "old-owner", 120L, "T09_IT_OLD_RELEASE"));
        assertEquals(0, consumedEventMapper.markStatus0CompensationRepublished(
                event.eventId(), "old-owner"));
        VideoStatusConsumedEvent stillNew = consumedRow(event.eventId());
        assertEquals("new-owner", stillNew.getCompensationLeaseToken(),
                "旧 token 不得释放或改写新 owner 的租约");
        assertNull(stillNew.getLastError());
        assertEquals(0, stillNew.getProcessStatus());
    }

    @Test
    void overSafeWindowIsNeverRepublishedEvenUnderAllThreeGates() throws Exception {
        var event = registerOldEvent(
                properties.getConsumerRecoveryAutoReplayMaxAgeSeconds() + 30);
        long id = consumedRow(event.eventId()).getId();
        properties.setStatus0CompensationEnabled(true);
        properties.setStatus0CompensationIntegrityConfirmed(true);
        properties.setStatus0CompensationAllowUnprovenReplay(true);

        clearInvocations(publisher);
        var result = compensation.runOnce(id - 1);

        assertEquals(0, result.claimed());
        assertEquals(0, result.republished());
        assertTrue(result.overSafeWindow() >= 1,
                "超龄事件只允许计入诊断计数");
        verify(publisher, never()).publish(
                argThat(e -> event.eventId().equals(e.eventId())), anyString());
        VideoStatusConsumedEvent row = consumedRow(event.eventId());
        assertEquals(0, row.getProcessStatus());
        assertEquals(0, row.getCompensationAttempts(),
                "超龄事件连租约都不应被领取");
        assertEquals(0L, current());
    }

    @Test
    void publishSucceededButWritebackFailedCanConvergeAgainWithSameEventId()
            throws Exception {
        var event = registerOldEvent(120);
        long id = consumedRow(event.eventId()).getId();
        properties.setStatus0CompensationEnabled(true);
        properties.setStatus0CompensationIntegrityConfirmed(true);
        properties.setStatus0CompensationAllowUnprovenReplay(true);

        VideoStatusConsumedEventMapper failingMapper = spy(consumedEventMapper);
        doThrow(new IllegalStateException("injected writeback failure"))
                .when(failingMapper)
                .markStatus0CompensationRepublished(eq(event.eventId()), anyString());
        var localClaim = new VideoStatusStatus0CompensationClaimService(
                failingMapper, properties);
        var localCompensation = new VideoStatusStatus0CompensationServiceImpl(
                failingMapper, localClaim, publisher, fingerprint,
                redis, properties, json);

        // 两次 publish 期间都关闭消费者：第一次只证明 publish 成功、回写失败；
        // 否则消费者会先把行推进到 status1，第二次补偿就不再是同一 RECEIVED 行。
        listenerRegistry.stop();
        try {
            clearInvocations(publisher);
            var first = localCompensation.runOnce(id - 1);

            assertEquals(0, first.republished());
            assertEquals(1, first.releasedOnFailure());
            verify(publisher, times(1)).publish(
                    argThat(e -> event.eventId().equals(e.eventId())), anyString());
            VideoStatusConsumedEvent released = consumedRow(event.eventId());
            assertEquals(0, released.getProcessStatus());
            assertNull(released.getCompensationLeaseToken());
            assertTrue(released.getLastError().startsWith("STATUS0_RELEASE_FAILED"));
            assertNotNull(released.getCompensationNextAttemptAt());

            // 把冷却/静默期调到已到期；消息仍在 Rabbit，重新开启消费者前再补偿一次。
            jdbc.update("UPDATE video_status_consumed_event "
                            + "SET compensation_next_attempt_at=DATE_SUB(NOW(3), INTERVAL 1 SECOND), "
                            + "last_attempt_at=DATE_SUB(NOW(3), INTERVAL 120 SECOND) "
                            + "WHERE event_id=?",
                    event.eventId());
            doCallRealMethod().when(failingMapper)
                    .markStatus0CompensationRepublished(anyString(), anyString());

            var second = compensation.runOnce(id - 1);
            assertEquals(1, second.republished());
            assertEquals(1, second.unprovenRepublished());
        } finally {
            // 两条同 eventId 消息现在一起由真实消费者处理，Lua/登记幂等必须只累计一次。
            listenerRegistry.start();
        }

        convergeToFlushedExactlyOnce(event.eventId(), 1L);
        assertEquals(2, consumedRow(event.eventId()).getCompensationAttempts(),
                "同一 eventId 的第二次补偿必须复用同一行/同一 eventId");
        assertEquals(1L, current(), "两次 publish 仍只能累计一次");
        await(this::queuesIdle);
    }

    @Test
    void currentAndProcessedBothMissingAreNotCompensatedByDefault() throws Exception {
        var event = registerOldEvent(120);
        assertEquals(VideoStatusService.ApplyResult.APPLIED, stats.apply(event));
        assertEquals(1, current());
        redis.delete(RedisKeyUtils.processedKey(event.eventId()));
        redis.delete(RedisKeyUtils.videoStatus(vid));
        assertFalse(Boolean.TRUE.equals(
                redis.hasKey(RedisKeyUtils.processedKey(event.eventId()))));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyUtils.videoStatus(vid))));
        long id = consumedRow(event.eventId()).getId();

        clearInvocations(publisher);
        var disabled = compensation.runOnce(id - 1);

        assertEquals(0, disabled.claimed(), "默认总开关关闭时不得领取");
        assertEquals(0, disabled.republished());
        verify(publisher, never()).publish(
                argThat(e -> event.eventId().equals(e.eventId())), anyString());
        assertFalse(Boolean.TRUE.equals(
                redis.hasKey(RedisKeyUtils.processedKey(event.eventId()))),
                "默认路径不得凭空重建 processed 证据");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(RedisKeyUtils.videoStatus(vid))),
                "默认路径不得凭空重建 current");
        assertEquals(0, consumedRow(event.eventId()).getProcessStatus());

        // 即使打开事故闸门，只要未显式允许 unproven replay，双丢失也只能诊断。
        properties.setStatus0CompensationEnabled(true);
        properties.setStatus0CompensationIntegrityConfirmed(true);
        properties.setStatus0CompensationAllowUnprovenReplay(false);
        clearInvocations(publisher);
        var incident = compensation.runOnce(id - 1);

        assertEquals(1, incident.claimed());
        assertEquals(0, incident.republished());
        assertEquals(1, incident.skippedNoProcessedEvidence());
        verify(publisher, never()).publish(
                argThat(e -> event.eventId().equals(e.eventId())), anyString());
        assertEquals(0, consumedRow(event.eventId()).getProcessStatus(),
                "双丢失时不得转 status3，也不得声称已补偿");
    }

    @Test
    void twoClaimThreadsConcurrentlyYieldExactlyOneLeaseToken() throws Exception {
        var event = registerOldEvent(120);
        long id = consumedRow(event.eventId()).getId();
        var claim = new VideoStatusStatus0CompensationClaimService(
                consumedEventMapper, properties);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<VideoStatusStatus0CompensationClaimService.ClaimBatch> first =
                    pool.submit(() -> {
                        ready.countDown();
                        go.await(5, TimeUnit.SECONDS);
                        return claim.claimNext(id - 1);
                    });
            Future<VideoStatusStatus0CompensationClaimService.ClaimBatch> second =
                    pool.submit(() -> {
                        ready.countDown();
                        go.await(5, TimeUnit.SECONDS);
                        return claim.claimNext(id - 1);
                    });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();

            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            long winners = (firstResult.claimed().isEmpty() ? 0 : 1)
                    + (secondResult.claimed().isEmpty() ? 0 : 1);
            assertEquals(1, winners, "两个 claim 线程只能有一个拿到租约");

            String winnerToken = firstResult.claimed().isEmpty()
                    ? secondResult.claimed().get(0).getCompensationLeaseToken()
                    : firstResult.claimed().get(0).getCompensationLeaseToken();
            assertNotNull(winnerToken);
            VideoStatusConsumedEvent row = consumedRow(event.eventId());
            assertEquals(winnerToken, row.getCompensationLeaseToken(),
                    "DB 中只能保留胜者的 token");
            assertEquals(1, row.getCompensationAttempts(),
                    "失败线程不得增加 attempts");
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    int freshVideo() {
        String username = "t09-it-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) "
                + "VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject(
                "SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 't09-isolated', 'test', 'test', '', '', '', '')", uid);
        int newVid = jdbc.queryForObject(
                "SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times) VALUES (?,0)", newVid);
        return newVid;
    }

    VideoStatusChangedEvent registerOldEvent(long ageSeconds) {
        var event = new VideoStatusChangedEvent(
                "t09-it-" + UUID.randomUUID(), vid, VideoStatusEventType.PLAY,
                1L, 1D, LocalDateTime.now(), 2, null);
        consumption.register(event, fingerprint.hash(event));
        setDbAge(event.eventId(), ageSeconds);
        return event;
    }

    void setDbAge(String eventId, long ageSeconds) {
        jdbc.update("UPDATE video_status_consumed_event "
                        + "SET consumed_at=DATE_SUB(NOW(3), INTERVAL ? SECOND), "
                        + "last_attempt_at=DATE_SUB(NOW(3), INTERVAL ? SECOND) "
                        + "WHERE event_id=?",
                ageSeconds, ageSeconds, eventId);
    }

    VideoStatusConsumedEvent consumedRow(String eventId) {
        VideoStatusConsumedEvent row = consumedEventMapper.selectByEventId(eventId);
        assertNotNull(row, "missing consumed event " + eventId);
        return row;
    }

    long current() {
        Object value = redis.opsForHash().get(
                RedisKeyUtils.videoStatus(vid), "playTimes");
        return Long.parseLong(String.valueOf(value));
    }

    void convergeToFlushedExactlyOnce(String eventId, long expectedCount)
            throws Exception {
        await(() -> {
            VideoStatusConsumedEvent row = consumedEventMapper.selectByEventId(eventId);
            if (row != null && row.getProcessStatus() == 1) {
                vidMutex.withLock(vid, () -> flushService.flushOneVideo(vid, 100));
            }
            return consumedEventMapper.selectByEventId(eventId) != null
                    && consumedEventMapper.selectByEventId(eventId).getProcessStatus() == 2;
        });
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_consumed_event WHERE event_id=?",
                Integer.class, eventId), "同一 eventId 不得新增第二行");
        assertEquals(expectedCount, current());
    }

    void resetKnownQueues() {
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

    boolean queuesIdle() {
        try {
            RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
            for (String queue : List.of(RabbitConstants.MAIN_QUEUE,
                    RabbitConstants.RETRY_QUEUE,
                    RabbitConstants.RETRY_QUEUE_30S,
                    RabbitConstants.RETRY_QUEUE_120S,
                    RabbitConstants.DEAD_QUEUE)) {
                var props = admin.getQueueInfo(queue);
                if (props != null && props.getMessageCount() != 0) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            throw new IllegalStateException(e);
        }
    }

    void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "condition did not converge in 30 seconds");
    }
}
