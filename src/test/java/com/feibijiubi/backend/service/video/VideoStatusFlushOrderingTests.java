package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.mq.VideoStatusBatchFlushScheduler;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusBatchFlushServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * T01 确定性故障基线：负增量落库顺序窗口。
 *
 * <p>复现顺序（与 MQ 到达先后无关，只与“状态提交”先后有关）：</p>
 * <ol>
 *   <li>+1 事件已成功写入 Redis，但 {@code markRedisApplied} 的状态提交暂停，
 *       事件仍为 RECEIVED(0)；</li>
 *   <li>-1 事件完成 Redis 与状态提交，变为 REDIS_APPLIED_PENDING_FLUSH(1)；</li>
 *   <li>触发 flush：{@code selectPendingForUpdate} 只能看到 -1，
 *       MySQL 基线为 0 时该批次的非负守卫不满足。</li>
 * </ol>
 *
 * <p>读代码确认的现状（{@code VideoStatusBatchFlushServiceImpl} +
 * {@code VideoStatusBatchFlushScheduler}）：{@code applyBatchDelta} 影响 0 行即抛
 * {@code VideoStatusFlushDataException}，调度器把它转成
 * {@code markFlushRepairRequired}，于是合法的 -1 事件被永久隔离为 status 3，
 * 不会延后等待 +1 落库。这里断言的就是该缺陷，不是期望行为。</p>
 *
 * <p><b>T03 改造要求</b>：本用例必须改为收敛回归断言——延后/重试而不是隔离，
 * +1 落库后 DB=Redis=0、两个事件均 FLUSHED(2)、无 status 3。
 * 在 T03 完成前不得用 {@code @Disabled} 掩盖本用例。</p>
 */
class VideoStatusFlushOrderingTests {

    private static final int VID = 42;

    private static VideoStatusConsumedEvent consumedEvent(
            long id,
            String eventId,
            long delta,
            int processStatus
    ) {
        VideoStatusConsumedEvent event = new VideoStatusConsumedEvent();
        event.setId(id);
        event.setEventId(eventId);
        event.setVid(VID);
        event.setEventType("PLAY");
        event.setDelta(delta);
        event.setProcessStatus(processStatus);
        event.setConsumerRetryCount(0);
        event.setLastAttemptAt(LocalDateTime.now());
        event.setConsumedAt(LocalDateTime.now());
        return event;
    }

    @Test
    void pausedPlusOneCommitQuarantinesLegalMinusOneInsteadOfDeferringIt() {
        // +1：Redis 已成功，markRedisApplied 未提交（status 仍为 0，flush 看不到）
        VideoStatusConsumedEvent pausedPlusOne =
                consumedEvent(1L, "plus-one", 1L, 0);
        // -1：Redis 与状态提交均已完成（status 1，flush 可见）
        VideoStatusConsumedEvent committedMinusOne =
                consumedEvent(2L, "minus-one", -1L, 1);

        var properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setSchedulingEnabled(true);

        var sets = mock(RedisSetOperations.class);
        when(sets.pop(anyString(), anyLong()))
                .thenReturn(Set.of(String.valueOf(VID)));

        var mutex = mock(VideoStatusVidMutex.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(mutex).withLock(eq(VID), any(Runnable.class));

        var consumedMapper = mock(VideoStatusConsumedEventMapper.class);
        // 真实 SQL 条件 process_status = 1：暂停中的 +1 不会被这一批选中
        List<VideoStatusConsumedEvent> rows =
                List.of(pausedPlusOne, committedMinusOne);
        when(consumedMapper.selectPendingForUpdate(eq(VID), anyInt()))
                .thenAnswer(call -> rows.stream()
                        .filter(row -> row.getProcessStatus() == 1)
                        .toList());
        when(consumedMapper.markFlushRepairRequired(anyList(), anyString()))
                .thenReturn(1);
        // 隔离后该 vid 已无 status=1 事件，调度器不再重新加入 dirty
        when(consumedMapper.countPendingByVid(VID)).thenReturn(0);

        var statusMapper = mock(VideoStatusMapper.class);
        // MySQL 基线为 0，非负守卫不满足 → 影响 0 行
        when(statusMapper.applyBatchDelta(eq(VID), any())).thenReturn(0);

        var flush = new VideoStatusBatchFlushServiceImpl(
                consumedMapper,
                statusMapper
        );
        var scheduler = new VideoStatusBatchFlushScheduler(
                properties,
                sets,
                flush,
                consumedMapper,
                mutex
        );

        scheduler.flushDirtyVideos();

        // 暂停中的 +1 从未进入本批：状态仍是 0，也不会被标记 FLUSHED
        assertEquals(0, pausedPlusOne.getProcessStatus());
        verify(consumedMapper, never()).markFlushed(List.of(1L));
        // 当前缺陷 1：-1 在“+1 尚未提交”的窗口里被判定为数据异常并永久隔离
        verify(consumedMapper).markFlushRepairRequired(
                eq(List.of(2L)),
                contains("非负")
        );
        // 当前缺陷 2：没有 markFlushed，也没有延后重试（不重新加入 dirty）
        verify(consumedMapper, never()).markFlushed(anyList());
        verify(sets, never()).add(anyString(), any(String[].class));
        // 数据未被篡改：只尝试了一次批量更新（被守卫拒绝），没有改成 0 或部分落库
        verify(statusMapper, times(1)).applyBatchDelta(eq(VID), any());
        verify(statusMapper, never()).increasePlayTimes(anyInt());
    }

    @Test
    void singleMinusOneBatchAtZeroBaselineNeverReachesFlushed() {
        // 同一窗口的另一种表现：即使没有 +1 参与，-1 单独成批也只能落到 status 3
        var properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setSchedulingEnabled(true);

        var sets = mock(RedisSetOperations.class);
        when(sets.pop(anyString(), anyLong()))
                .thenReturn(Set.of(String.valueOf(VID)));
        var mutex = mock(VideoStatusVidMutex.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(mutex).withLock(eq(VID), any(Runnable.class));

        var consumedMapper = mock(VideoStatusConsumedEventMapper.class);
        when(consumedMapper.selectPendingForUpdate(eq(VID), anyInt()))
                .thenReturn(List.of(consumedEvent(7L, "only-minus", -1L, 1)));
        when(consumedMapper.markFlushRepairRequired(anyList(), anyString()))
                .thenReturn(1);
        when(consumedMapper.countPendingByVid(VID)).thenReturn(0);

        var statusMapper = mock(VideoStatusMapper.class);
        when(statusMapper.applyBatchDelta(eq(VID), any())).thenReturn(0);

        new VideoStatusBatchFlushScheduler(
                properties,
                sets,
                new VideoStatusBatchFlushServiceImpl(consumedMapper, statusMapper),
                consumedMapper,
                mutex
        ).flushDirtyVideos();

        verify(consumedMapper).markFlushRepairRequired(eq(List.of(7L)), anyString());
        verify(consumedMapper, never()).markFlushed(anyList());
    }
}
