package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusOutbox;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.mq.VideoStatusBatchFlushScheduler;
import com.feibijiubi.backend.mq.VideoStatusDirtyRecoveryScheduler;
import com.feibijiubi.backend.mq.VideoStatusEventPublisher;
import com.feibijiubi.backend.mq.VideoStatusOutboxClaimService;
import com.feibijiubi.backend.mq.VideoStatusOutboxRelay;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T06 调度隔离与执行预算的纯构造单测：不启动 Spring、不连 MySQL/Redis/RabbitMQ。
 *
 * <p>固定四件事：</p>
 * <ol>
 *   <li>投递逐条领取（{@code claimBatch(1)}），每轮最多 {@code outbox-batch-size} 条；</li>
 *   <li>单轮预算用尽后不再领取/处理新工作，未处理的工作原样留在 Outbox / dirty Set / 数据库；</li>
 *   <li>一个视频锁忙（100ms 等待超时）不影响同轮其它视频，默认 10 秒等待语义不变；</li>
 *   <li>恢复调度预算用尽时把剩余 vid 留给数据库下一轮反哺，不在内存里攒队列。</li>
 * </ol>
 */
class VideoStatusSchedulingIsolationTests {

    private static final String EVENT_ID = "evt-t06";
    private static final String LEASE = "lease-t06";

    // ------------------------------------------------------------ 投递：逐条领取

    @Test
    void relayClaimsOneEventAtATimeAndStopsAtOutboxBatchSize() throws Exception {
        VideoStatusProperties properties = relayProperties();
        properties.setOutboxBatchSize(2);

        VideoStatusOutboxClaimService claimService =
                mock(VideoStatusOutboxClaimService.class);
        // 每轮最多 2 条：即使还有第 3 条可领，本轮也必须停下。
        when(claimService.claimBatch(1)).thenReturn(
                List.of(outbox()),
                List.of(outbox()),
                List.of(outbox())
        );
        VideoStatusOutboxMapper outboxMapper = mock(VideoStatusOutboxMapper.class);
        VideoStatusEventPublisher publisher = mock(VideoStatusEventPublisher.class);
        JsonUtils jsonUtils = mock(JsonUtils.class);
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenReturn(event());

        new VideoStatusOutboxRelay(claimService, outboxMapper, publisher,
                properties, jsonUtils).relay();

        // 逐条领取：每次都只领 1 条，绝不预领一整批
        verify(claimService, times(2)).claimBatch(1);
        verify(claimService, never()).claimBatch();
        verify(publisher, times(2)).publish(any(VideoStatusChangedEvent.class), eq(LEASE));
        verify(outboxMapper, times(2)).markSent(eq(EVENT_ID), eq(LEASE),
                any(LocalDateTime.class));
    }

    @Test
    void relayStopsBeforeNextClaimWhenRoundBudgetIsExhausted() throws Exception {
        VideoStatusProperties properties = relayProperties();
        properties.setOutboxBatchSize(100);
        properties.setRoundBudgetMs(1);

        VideoStatusOutboxClaimService claimService =
                mock(VideoStatusOutboxClaimService.class);
        when(claimService.claimBatch(1)).thenReturn(List.of(outbox()));
        VideoStatusOutboxMapper outboxMapper = mock(VideoStatusOutboxMapper.class);
        VideoStatusEventPublisher publisher = mock(VideoStatusEventPublisher.class);
        JsonUtils jsonUtils = mock(JsonUtils.class);
        when(jsonUtils.fromJson(anyString(), eq(VideoStatusChangedEvent.class)))
                .thenReturn(event());
        // 模拟一次慢发布（真实环境里由 confirm timeout 界定）：远超 1ms 预算
        doAnswer(call -> {
            Thread.sleep(25L);
            return null;
        }).when(publisher).publish(any(VideoStatusChangedEvent.class), anyString());

        new VideoStatusOutboxRelay(claimService, outboxMapper, publisher,
                properties, jsonUtils).relay();

        // 预算检查在“下一次领取之前”：本轮只领了一次，剩下的仍在 Outbox 表里
        verify(claimService, times(1)).claimBatch(1);
        verify(publisher, times(1)).publish(any(VideoStatusChangedEvent.class), eq(LEASE));
        // 慢发布本身不被强杀，照常回写成功
        verify(outboxMapper).markSent(eq(EVENT_ID), eq(LEASE), any(LocalDateTime.class));
    }

    @Test
    void relayStopsWhenNoPendingEventIsLeft() throws Exception {
        VideoStatusProperties properties = relayProperties();
        properties.setOutboxBatchSize(10);

        VideoStatusOutboxClaimService claimService =
                mock(VideoStatusOutboxClaimService.class);
        when(claimService.claimBatch(1)).thenReturn(List.of());
        VideoStatusOutboxMapper outboxMapper = mock(VideoStatusOutboxMapper.class);

        new VideoStatusOutboxRelay(claimService, outboxMapper,
                mock(VideoStatusEventPublisher.class), properties,
                mock(JsonUtils.class)).relay();

        // 没有待投递事件就结束本轮，不把预算空转完
        verify(claimService, times(1)).claimBatch(1);
        verify(outboxMapper, never()).markSent(anyString(), anyString(), any());
    }

    // ------------------------------------------------------------ 领取：limit 契约

    @Test
    void claimBatchUsesRequestedLimitAndKeepsNoArgDefault() {
        VideoStatusOutboxMapper mapper = mock(VideoStatusOutboxMapper.class);
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setOutboxBatchSize(100);
        when(mapper.selectPendingForUpdate(anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of());
        VideoStatusOutboxClaimService claimService =
                new VideoStatusOutboxClaimService(mapper, properties);

        claimService.claimBatch(1);
        verify(mapper).selectPendingForUpdate(eq(1), any(LocalDateTime.class));

        claimService.claimBatch();
        verify(mapper).selectPendingForUpdate(eq(100), any(LocalDateTime.class));
    }

    @Test
    void claimBatchRejectsNonPositiveLimitBeforeTouchingDatabase() {
        VideoStatusOutboxMapper mapper = mock(VideoStatusOutboxMapper.class);
        VideoStatusOutboxClaimService claimService = new VideoStatusOutboxClaimService(
                mapper,
                new VideoStatusProperties()
        );

        assertThrows(IllegalArgumentException.class, () -> claimService.claimBatch(0));
        assertThrows(IllegalArgumentException.class, () -> claimService.claimBatch(-1));
        assertThrows(IllegalArgumentException.class, () -> claimService.claimBatch(
                VideoStatusProperties.MAX_BATCH_SIZE + 1));
        verifyNoInteractions(mapper);
    }

    // ------------------------------------------------------------ 落库：预算 + 锁忙隔离

    @Test
    void flushRoundBudgetRequeuesEveryUnprocessedDirtyWithoutRemoving() {
        VideoStatusProperties properties = flushProperties();
        properties.setRoundBudgetMs(1);

        RedisSetOperations sets = mock(RedisSetOperations.class);
        Set<String> dirty = new LinkedHashSet<>(List.of("7", "8", "9"));
        when(sets.pop(eq(RedisKeyUtils.dirtyVideo()), anyLong())).thenAnswer(call -> {
            Set<String> popped = new LinkedHashSet<>(dirty);
            dirty.clear();
            return popped;
        });

        VideoStatusBatchFlushService flushService = mock(VideoStatusBatchFlushService.class);
        // 一个视频落库本身就超过 1ms 预算：本轮处理完 7 之后必须停下
        doAnswer(call -> {
            Thread.sleep(25L);
            return null;
        }).when(flushService).flushOneVideo(anyInt(), anyInt());

        VideoStatusVidMutex mutex = mock(VideoStatusVidMutex.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(2)).run();
            return null;
        }).when(mutex).withLock(anyInt(), anyLong(), any(Runnable.class));

        new VideoStatusBatchFlushScheduler(properties, sets, flushService,
                mock(VideoStatusConsumedEventMapper.class), mutex).flushDirtyVideos();

        verify(flushService, times(1)).flushOneVideo(eq(7), anyInt());
        // 未处理的 dirty 全部补回，绝不 SREM
        ArgumentCaptor<String> requeued = ArgumentCaptor.forClass(String.class);
        verify(sets, times(2)).add(eq(RedisKeyUtils.dirtyVideo()), requeued.capture());
        assertEquals(Set.of("8", "9"), Set.copyOf(requeued.getAllValues()));
        verify(sets, never()).remove(anyString(), any(String[].class));
    }

    @Test
    void flushLockBusyOnOneVidDoesNotBlockHealthyVidInSameRound() {
        VideoStatusProperties properties = flushProperties();
        properties.setRoundBudgetMs(5_000L);

        RedisSetOperations sets = mock(RedisSetOperations.class);
        Set<String> dirty = new LinkedHashSet<>(List.of("7", "8"));
        when(sets.pop(eq(RedisKeyUtils.dirtyVideo()), anyLong())).thenAnswer(call -> {
            Set<String> popped = new LinkedHashSet<>(dirty);
            dirty.clear();
            return popped;
        });

        VideoStatusBatchFlushService flushService = mock(VideoStatusBatchFlushService.class);
        VideoStatusVidMutex mutex = mock(VideoStatusVidMutex.class);
        // 7 号视频被别人长时间持有：100ms 等待后超时
        doThrow(new RetryableMessageException("获取视频统计锁超时，vid=7"))
                .when(mutex).withLock(eq(7), anyLong(), any(Runnable.class));
        doAnswer(call -> {
            ((Runnable) call.getArgument(2)).run();
            return null;
        }).when(mutex).withLock(eq(8), anyLong(), any(Runnable.class));

        new VideoStatusBatchFlushScheduler(properties, sets, flushService,
                mock(VideoStatusConsumedEventMapper.class), mutex).flushDirtyVideos();

        // 锁忙的 vid 只把 dirty 放回去，健康 vid 在同一轮继续落库
        verify(flushService, never()).flushOneVideo(eq(7), anyInt());
        verify(flushService, times(1)).flushOneVideo(eq(8), anyInt());
        verify(sets).add(eq(RedisKeyUtils.dirtyVideo()), eq("7"));
        verify(sets, never()).remove(anyString(), any(String[].class));
    }

    @Test
    void flushSchedulerWaitsAtMost100MillisForVidLock() {
        VideoStatusProperties properties = flushProperties();

        RedisSetOperations sets = mock(RedisSetOperations.class);
        when(sets.pop(eq(RedisKeyUtils.dirtyVideo()), anyLong()))
                .thenReturn(Set.of("7"));
        VideoStatusBatchFlushService flushService = mock(VideoStatusBatchFlushService.class);
        VideoStatusVidMutex mutex = mock(VideoStatusVidMutex.class);

        new VideoStatusBatchFlushScheduler(properties, sets, flushService,
                mock(VideoStatusConsumedEventMapper.class), mutex).flushDirtyVideos();

        // 调度轮用 100ms 短等待，而不是消费/重建路径的 10 秒
        verify(mutex).withLock(eq(7),
                eq(VideoStatusVidMutex.SCHEDULER_WAIT_TIME_MILLIS),
                any(Runnable.class));
        assertEquals(100L, VideoStatusVidMutex.SCHEDULER_WAIT_TIME_MILLIS);
    }

    @Test
    void vidMutexKeepsTenSecondDefaultWaitAndSupportsShortSchedulerWait()
            throws Exception {
        RLock lock = mock(RLock.class);
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        VideoStatusVidMutex mutex = new VideoStatusVidMutex(redisson);

        mutex.withLock(7, VideoStatusVidMutex.SCHEDULER_WAIT_TIME_MILLIS,
                (Runnable) () -> {
                });
        verify(lock).tryLock(100L, TimeUnit.MILLISECONDS);

        // 默认重载（消费/重建）保持 10 秒等待，只是换算成毫秒
        mutex.withLock(8, (Runnable) () -> {
        });
        verify(lock).tryLock(10_000L, TimeUnit.MILLISECONDS);
        assertEquals(10_000L, VideoStatusVidMutex.DEFAULT_WAIT_TIME_MILLIS);
    }

    // ------------------------------------------------------------ 恢复：预算耗尽留 DB

    @Test
    void recoveryRoundBudgetLeavesRemainingVidsToDatabaseFeed() {
        VideoStatusProperties properties = flushProperties();
        properties.setRoundBudgetMs(1);

        VideoStatusConsumedEventMapper mapper = mock(VideoStatusConsumedEventMapper.class);
        when(mapper.selectPendingVidsAfter(isNull(), anyInt())).thenReturn(List.of(11, 12, 13));

        RedisSetOperations sets = mock(RedisSetOperations.class);
        doAnswer(call -> {
            Thread.sleep(25L);
            return 1L;
        }).when(sets).add(eq(RedisKeyUtils.dirtyVideo()), any(String[].class));

        new VideoStatusDirtyRecoveryScheduler(properties, mapper, sets).recoverDirtyVids();

        // 预算用尽即停：剩余 vid 仍在消费事件表里，下一轮由数据库再次反哺
        ArgumentCaptor<String> recovered = ArgumentCaptor.forClass(String.class);
        verify(sets, times(1)).add(eq(RedisKeyUtils.dirtyVideo()), recovered.capture());
        assertEquals(List.of("11"), recovered.getAllValues());
    }

    // ------------------------------------------------------------ helpers

    private VideoStatusProperties relayProperties() {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setOutboxRelayEnabled(true);
        return properties;
    }

    private VideoStatusProperties flushProperties() {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setSchedulingEnabled(true);
        return properties;
    }

    private VideoStatusOutbox outbox() {
        VideoStatusOutbox outbox = new VideoStatusOutbox();
        outbox.setId(1L);
        outbox.setEventId(EVENT_ID);
        outbox.setAggregateId(7);
        outbox.setEventType("PLAY");
        outbox.setPayload("{}");
        outbox.setStatus(1);
        outbox.setRetryCount(0);
        outbox.setLeaseToken(LEASE);
        outbox.setSendingAt(LocalDateTime.now());
        return outbox;
    }

    private VideoStatusChangedEvent event() {
        return new VideoStatusChangedEvent(EVENT_ID, 7, VideoStatusEventType.PLAY,
                1L, 1D, LocalDateTime.now(), 2, null);
    }

    @Test
    void roundBudgetDefaultsToFiveSeconds() {
        // 预算默认值属于契约：5000ms 与 T04 其它时长配置同为毫秒单位
        assertEquals(5_000L, new VideoStatusProperties().getRoundBudgetMs());
        assertTrue(new VideoStatusProperties().getRoundBudgetMs()
                <= VideoStatusProperties.MAX_SCHEDULER_DELAY_MS);
    }
}
