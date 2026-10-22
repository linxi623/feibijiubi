package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.config.VideoStatusRuntimeRoles;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mq.VideoStatusBatchFlushScheduler;
import com.feibijiubi.backend.mq.VideoStatusDirtyRecoveryScheduler;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T04：配置契约与运行角色。
 *
 * <p>覆盖三件事：</p>
 * <ol>
 *   <li>启动期参数校验（批量、时长、重试、replay 安全窗口与幂等 TTL 的交叉约束）；</li>
 *   <li>四角色矩阵：async / relay / listener / scheduling 的合法组合不被误拒，
 *       且单机无人落库能被诊断出来；</li>
 *   <li>{@code outbox-relay-enabled=false} 只影响投递，不影响批量落库与 dirty 恢复调度。</li>
 * </ol>
 *
 * <p>本类不启动 Spring 上下文、不连接 MySQL/Redis/RabbitMQ，全部使用内存对象与 Mockito。</p>
 */
class VideoStatusRuntimeRoleContractTests {

    private VideoStatusProperties validProperties() {
        // 默认值即 application.yml.example 的形态（除 async/relay 开关）
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setOutboxRelayEnabled(true);
        properties.setSchedulingEnabled(true);
        return properties;
    }

    // ---------------------------------------------------------------- 参数校验

    @Test
    void defaultValuesPassStartupValidation() {
        assertDoesNotThrow(new VideoStatusProperties()::validate);
    }

    @Test
    void zeroOrNegativeBatchSizeIsRejected() {
        var properties = validProperties();
        properties.setOutboxBatchSize(0);
        var error = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(error.getMessage().contains("outbox-batch-size"), error.getMessage());

        var negativeFlush = validProperties();
        negativeFlush.setFlushEventBatchSize(-1);
        var flushError = assertThrows(IllegalStateException.class, negativeFlush::validate);
        assertTrue(flushError.getMessage().contains("flush-event-batch-size"),
                flushError.getMessage());

        var tooLarge = validProperties();
        tooLarge.setFlushDirtyBatchSize(VideoStatusProperties.MAX_BATCH_SIZE + 1);
        assertThrows(IllegalStateException.class, tooLarge::validate);
    }

    @Test
    void nonPositiveOrAbsurdDurationsAreRejected() {
        var zeroDelay = validProperties();
        zeroDelay.setOutboxFixedDelayMs(0);
        assertTrue(assertThrows(IllegalStateException.class, zeroDelay::validate)
                .getMessage().contains("outbox-fixed-delay-ms"));

        var negativeFlushDelay = validProperties();
        negativeFlushDelay.setFlushFixedDelayMs(-500);
        assertThrows(IllegalStateException.class, negativeFlushDelay::validate);

        var zeroRecoveryDelay = validProperties();
        zeroRecoveryDelay.setFlushRecoveryFixedDelayMs(0);
        assertThrows(IllegalStateException.class, zeroRecoveryDelay::validate);

        var zeroConfirm = validProperties();
        zeroConfirm.setPublishConfirmTimeoutSeconds(0);
        assertTrue(assertThrows(IllegalStateException.class, zeroConfirm::validate)
                .getMessage().contains("publish-confirm-timeout-seconds"));

        var zeroLease = validProperties();
        zeroLease.setOutboxLeaseSeconds(0);
        assertTrue(assertThrows(IllegalStateException.class, zeroLease::validate)
                .getMessage().contains("outbox-lease-seconds"));

        var tooLongDelay = validProperties();
        tooLongDelay.setOutboxFixedDelayMs(VideoStatusProperties.MAX_SCHEDULER_DELAY_MS + 1);
        assertThrows(IllegalStateException.class, tooLongDelay::validate);
    }

    @Test
    void retryCountMustBePositiveAndBounded() {
        var zeroRetries = validProperties();
        zeroRetries.setConsumerMaxRetries(0);
        assertTrue(assertThrows(IllegalStateException.class, zeroRetries::validate)
                .getMessage().contains("consumer-max-retries"));

        var tooManyRetries = validProperties();
        tooManyRetries.setConsumerMaxRetries(VideoStatusProperties.MAX_CONSUMER_MAX_RETRIES + 1);
        assertThrows(IllegalStateException.class, tooManyRetries::validate);
    }

    @Test
    void replaySafetyWindowMustStayInsideRedisIdempotencyTtl() {
        var zeroWindow = validProperties();
        zeroWindow.setConsumerRecoveryAutoReplayMaxAgeSeconds(0);
        assertTrue(assertThrows(IllegalStateException.class, zeroWindow::validate)
                .getMessage().contains("consumer-recovery-auto-replay-max-age-seconds"));

        var equalToTtl = validProperties();
        equalToTtl.setRedisEventTtlDays(1);
        equalToTtl.setConsumerRecoveryAutoReplayMaxAgeSeconds(86_400);
        var error = assertThrows(IllegalStateException.class, equalToTtl::validate);
        assertTrue(error.getMessage().contains("86400"), error.getMessage());

        var longerThanTtl = validProperties();
        longerThanTtl.setRedisEventTtlDays(1);
        longerThanTtl.setConsumerRecoveryAutoReplayMaxAgeSeconds(604_800);
        assertThrows(IllegalStateException.class, longerThanTtl::validate);

        var insideTtl = validProperties();
        insideTtl.setRedisEventTtlDays(1);
        insideTtl.setConsumerRecoveryAutoReplayMaxAgeSeconds(3_600);
        assertDoesNotThrow(insideTtl::validate);

        var zeroTtl = validProperties();
        zeroTtl.setRedisEventTtlDays(0);
        assertTrue(assertThrows(IllegalStateException.class, zeroTtl::validate)
                .getMessage().contains("redis-event-ttl-days"));
    }

    @Test
    void relayFlagIsIndependentOfParameterValidation() {
        var properties = validProperties();
        properties.setOutboxRelayEnabled(false);
        assertDoesNotThrow(properties::validate);
    }

    // ---------------------------------------------------------------- 四角色矩阵

    @Test
    void allFourRolesEnabledHasNoDiagnostics() {
        var roles = new VideoStatusRuntimeRoles(validProperties(), true);

        assertEquals(new VideoStatusRuntimeRoles.RoleMatrix(true, true, true, true),
                roles.matrix());
        assertTrue(roles.diagnostics().isEmpty(), roles.diagnostics().toString());
    }

    @Test
    void asyncDisabledMeansPublishAndFlushOffButListenerIndependent() {
        var properties = validProperties();
        properties.setAsyncEnabled(false);
        properties.setOutboxRelayEnabled(true);
        properties.setSchedulingEnabled(true);

        var roles = new VideoStatusRuntimeRoles(properties, true);

        // async=false 时 Outbox 不产生事件，投递与落库角色都无效；监听器由 RabbitMQ 开关独立决定
        assertEquals(new VideoStatusRuntimeRoles.RoleMatrix(false, false, true, false),
                roles.matrix());
        assertFalse(roles.diagnostics().isEmpty());
    }

    @Test
    void relayDisabledKeepsFlushAndConsumeRolesAndIsNotRejected() {
        var properties = validProperties();
        properties.setOutboxRelayEnabled(false);

        var roles = new VideoStatusRuntimeRoles(properties, true);

        assertEquals(new VideoStatusRuntimeRoles.RoleMatrix(true, false, true, true),
                roles.matrix());
        List<String> diagnostics = roles.diagnostics();
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("outbox-relay-enabled=false"),
                diagnostics.get(0));
    }

    @Test
    void asyncWithoutSchedulingIsLegalButDiagnosedAsNobodyFlushes() {
        var properties = validProperties();
        properties.setSchedulingEnabled(false);

        var roles = new VideoStatusRuntimeRoles(properties, false);

        // 多角色部署/维护窗口的合法组合：不能抛异常拒绝
        assertEquals(new VideoStatusRuntimeRoles.RoleMatrix(true, true, false, false),
                roles.matrix());
        String joined = String.join("\n", roles.diagnostics());
        assertTrue(joined.contains("scheduling-enabled=false"), joined);
        assertTrue(joined.contains("无人落库"), joined);
        assertTrue(joined.contains("监听器未自动启动"), joined);
    }

    @Test
    void relayDisabledDoesNotStopBatchFlushScheduler() {
        var properties = validProperties();
        properties.setOutboxRelayEnabled(false);
        var sets = mock(RedisSetOperations.class);
        when(sets.pop(anyString(), anyLong())).thenReturn(Set.of());

        new VideoStatusBatchFlushScheduler(
                properties,
                sets,
                mock(VideoStatusBatchFlushService.class),
                mock(VideoStatusConsumedEventMapper.class),
                mock(VideoStatusVidMutex.class)
        ).flushDirtyVideos();

        verify(sets).pop(eq(RedisKeyUtils.dirtyVideo()), eq(100L));
    }

    @Test
    void relayDisabledDoesNotStopDirtyRecoveryScheduler() {
        var properties = validProperties();
        properties.setOutboxRelayEnabled(false);
        var mapper = mock(VideoStatusConsumedEventMapper.class);
        when(mapper.selectPendingVidsAfter(isNull(), anyInt())).thenReturn(List.of());

        new VideoStatusDirtyRecoveryScheduler(
                properties,
                mapper,
                mock(RedisSetOperations.class)
        ).recoverDirtyVids();

        verify(mapper).selectPendingVidsAfter(isNull(), eq(100));
    }

    @Test
    void schedulingDisabledStopsFlushSchedulerAsBefore() {
        var properties = validProperties();
        properties.setSchedulingEnabled(false);
        var sets = mock(RedisSetOperations.class);

        new VideoStatusBatchFlushScheduler(
                properties,
                sets,
                mock(VideoStatusBatchFlushService.class),
                mock(VideoStatusConsumedEventMapper.class),
                mock(VideoStatusVidMutex.class)
        ).flushDirtyVideos();

        verify(sets, org.mockito.Mockito.never()).pop(anyString(), anyLong());
    }
}
