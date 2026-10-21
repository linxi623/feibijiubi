package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.event.VideoStatusDelta;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.mq.VideoStatusBatchFlushScheduler;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusBatchFlushServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T03 flush 单测夹具：用内存实现代替真实 MySQL / Redis，并注入可控时钟。
 *
 * <p>目的：让“负增量顺序窗口”“退避到期”“拆批”这类时序断言完全确定，
 * 不依赖 sleep 碰运气。行为尽量贴近真实实现：</p>
 * <ul>
 *   <li>{@code selectPendingForUpdate} 只返回 {@code process_status = 1}，
 *       并复刻 SQL 的“正增量优先 + id 升序 + LIMIT”；
 *   <li>{@code selectPendingNegativeFirstForUpdate}（T03 上限失败时的有界备选）
 *       同样只返回 status 1，但复刻“负增量优先 + id 升序 + LIMIT”；</li>
 *   <li>{@code applyBatchDelta} 复刻 T02 的 0 行守卫：任一字段结果越界（&lt;0 或溢出）
 *       就整体返回 0 行，绝不部分落库；</li>
 *   <li>{@code markFlushed} / {@code markFlushRepairRequired} 只更新 status 1 的行，
 *       返回真实影响行数；</li>
 *   <li>dirty Set 用真实集合语义（SADD/SPOP），退避由 {@link MutableClock} 控制。</li>
 * </ul>
 */
final class VideoStatusFlushTestFixture {

    static final int DEFAULT_VID = 42;

    private final MutableClock clock = new MutableClock();
    private final Set<String> dirty = new LinkedHashSet<>();
    private final List<VideoStatusConsumedEvent> events = new ArrayList<>();
    private final Map<Integer, VideoStatus> rows = new HashMap<>();
    private final List<List<Long>> repairedIds = new ArrayList<>();
    private final List<String> repairErrors = new ArrayList<>();
    private final VideoStatusProperties properties = new VideoStatusProperties();

    private final VideoStatusConsumedEventMapper consumedMapper;
    private final VideoStatusMapper statusMapper;
    final VideoStatusBatchFlushScheduler scheduler;

    int lockCalls;
    int pendingQueries;
    int negativeFirstQueries;
    int applyCalls;

    VideoStatusFlushTestFixture() {
        properties.setAsyncEnabled(true);
        properties.setSchedulingEnabled(true);

        RedisSetOperations sets = mock(RedisSetOperations.class);
        when(sets.pop(anyString(), anyLong())).thenAnswer(call -> {
            Set<String> popped = new LinkedHashSet<>(dirty);
            dirty.clear();
            return popped;
        });
        when(sets.add(anyString(), any(String[].class))).thenAnswer(call -> {
            Object[] args = call.getArguments();
            for (int i = 1; i < args.length; i++) {
                dirty.add(String.valueOf(args[i]));
            }
            return (long) (args.length - 1);
        });

        VideoStatusVidMutex mutex = mock(VideoStatusVidMutex.class);
        doAnswer(call -> {
            lockCalls++;
            ((Runnable) call.getArgument(2)).run();
            return null;
        }).when(mutex).withLock(anyInt(), anyLong(), any(Runnable.class));

        statusMapper = mock(VideoStatusMapper.class);
        when(statusMapper.selectByVid(anyInt())).thenAnswer(call -> {
            VideoStatus row = rows.get((Integer) call.getArgument(0));
            return row == null ? null : copy(row);
        });
        when(statusMapper.applyBatchDelta(anyInt(), any(VideoStatusDelta.class)))
                .thenAnswer(call -> {
                    applyCalls++;
                    Integer vid = call.getArgument(0);
                    VideoStatusDelta delta = call.getArgument(1);
                    VideoStatus row = rows.get(vid);
                    if (row == null) {
                        return 0;
                    }
                    long[] base = values(row);
                    long[] increment = deltaValues(delta);
                    long[] next = new long[base.length];
                    for (int i = 0; i < base.length; i++) {
                        try {
                            next[i] = Math.addExact(base[i], increment[i]);
                        } catch (ArithmeticException e) {
                            return 0;
                        }
                        if (next[i] < 0) {
                            return 0;
                        }
                    }
                    write(row, next);
                    return 1;
                });

        consumedMapper = mock(VideoStatusConsumedEventMapper.class);
        when(consumedMapper.selectPendingForUpdate(anyInt(), anyInt()))
                .thenAnswer(call -> {
                    pendingQueries++;
                    Integer vid = call.getArgument(0);
                    int limit = call.getArgument(1);
                    return events.stream()
                            .filter(event -> event.getVid().equals(vid))
                            .filter(event -> event.getProcessStatus() == 1)
                            .sorted(Comparator
                                    .comparingInt((VideoStatusConsumedEvent event)
                                            -> event.getDelta() > 0 ? 0 : 1)
                                    .thenComparing(VideoStatusConsumedEvent::getId))
                            .limit(limit)
                            .toList();
                });
        when(consumedMapper.selectPendingNegativeFirstForUpdate(
                anyInt(), anyInt()))
                .thenAnswer(call -> {
                    negativeFirstQueries++;
                    Integer vid = call.getArgument(0);
                    int limit = call.getArgument(1);
                    // 复刻 SQL 的“负增量优先 + id 升序 + LIMIT”，同一 vid / status 1。
                    return events.stream()
                            .filter(event -> event.getVid().equals(vid))
                            .filter(event -> event.getProcessStatus() == 1)
                            .sorted(Comparator
                                    .comparingInt((VideoStatusConsumedEvent event)
                                            -> event.getDelta() < 0 ? 0 : 1)
                                    .thenComparing(VideoStatusConsumedEvent::getId))
                            .limit(limit)
                            .toList();
                });
        when(consumedMapper.markFlushed(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            int updated = 0;
            for (VideoStatusConsumedEvent event : events) {
                if (ids.contains(event.getId()) && event.getProcessStatus() == 1) {
                    event.setProcessStatus(2);
                    updated++;
                }
            }
            return updated;
        });
        when(consumedMapper.markFlushRepairRequired(anyList(), anyString()))
                .thenAnswer(call -> {
                    List<Long> ids = call.getArgument(0);
                    String error = call.getArgument(1);
                    repairedIds.add(List.copyOf(ids));
                    repairErrors.add(error);
                    int updated = 0;
                    for (VideoStatusConsumedEvent event : events) {
                        if (ids.contains(event.getId())
                                && event.getProcessStatus() == 1) {
                            event.setProcessStatus(3);
                            updated++;
                        }
                    }
                    return updated;
                });
        when(consumedMapper.countPendingByVid(anyInt())).thenAnswer(call -> {
            Integer vid = call.getArgument(0);
            return (int) events.stream()
                    .filter(event -> event.getVid().equals(vid))
                    .filter(event -> event.getProcessStatus() == 1)
                    .count();
        });

        VideoStatusBatchFlushServiceImpl flushService =
                new VideoStatusBatchFlushServiceImpl(consumedMapper, statusMapper);
        scheduler = new VideoStatusBatchFlushScheduler(
                properties,
                sets,
                flushService,
                consumedMapper,
                mutex,
                clock
        );
    }

    VideoStatusConsumedEvent addEvent(long id, String eventType, long delta, int status) {
        VideoStatusConsumedEvent event = new VideoStatusConsumedEvent();
        event.setId(id);
        event.setEventId("event-" + id);
        event.setVid(DEFAULT_VID);
        event.setEventType(eventType);
        event.setDelta(delta);
        event.setProcessStatus(status);
        event.setConsumerRetryCount(0);
        event.setLastAttemptAt(LocalDateTime.now());
        event.setConsumedAt(LocalDateTime.now());
        events.add(event);
        return event;
    }

    VideoStatusConsumedEvent addEvent(long id, long delta, int status) {
        return addEvent(id, "PLAY", delta, status);
    }

    void addRow(int vid, long play) {
        rows.put(vid, new VideoStatus(vid, play, 0L, 0L, 0L, 0L, 0L, 0L, 0L));
    }

    void addRow(int vid, long play, long like, long unlike) {
        rows.put(vid, new VideoStatus(vid, play, like, unlike, 0L, 0L, 0L, 0L, 0L));
    }

    void removeRow(int vid) {
        rows.remove(vid);
    }

    void setEventStatus(long id, int status) {
        events.stream()
                .filter(event -> event.getId() == id)
                .forEach(event -> event.setProcessStatus(status));
    }

    void setFlushEventBatchSize(int size) {
        properties.setFlushEventBatchSize(size);
    }

    /**
     * 强制批量更新返回指定行数，用于覆盖“Java 侧守卫成立、SQL 却未命中行”的暂态分支。
     */
    void forceApplyBatchDeltaResult(int result) {
        when(statusMapper.applyBatchDelta(anyInt(), any(VideoStatusDelta.class)))
                .thenReturn(result);
    }

    void markDirty(int vid) {
        dirty.add(String.valueOf(vid));
    }

    boolean dirtyContains(int vid) {
        return dirty.contains(String.valueOf(vid));
    }

    long playOf(int vid) {
        return values(rows.get(vid))[0];
    }

    long likeOf(int vid) {
        return values(rows.get(vid))[1];
    }

    long unlikeOf(int vid) {
        return values(rows.get(vid))[2];
    }

    int statusOf(long id) {
        return events.stream()
                .filter(event -> event.getId() == id)
                .findFirst()
                .orElseThrow()
                .getProcessStatus();
    }

    List<List<Long>> repairedIds() {
        return repairedIds;
    }

    List<String> repairErrors() {
        return repairErrors;
    }

    void advance(long millis) {
        clock.advance(millis);
    }

    void runScheduler() {
        scheduler.flushDirtyVideos();
    }

    private static long[] values(VideoStatus status) {
        return new long[]{
                status.getPlayTimes(),
                status.getLikeTimes(),
                status.getUnlikeTimes(),
                status.getCommentTimes(),
                status.getCoinTimes(),
                status.getShareTimes(),
                status.getCollectTimes(),
                status.getDanmuTimes()
        };
    }

    private static long[] deltaValues(VideoStatusDelta delta) {
        return new long[]{
                delta.playDelta(),
                delta.likeDelta(),
                delta.unlikeDelta(),
                delta.commentDelta(),
                delta.coinDelta(),
                delta.shareDelta(),
                delta.collectDelta(),
                delta.danmuDelta()
        };
    }

    private static void write(VideoStatus status, long[] next) {
        status.setPlayTimes(next[0]);
        status.setLikeTimes(next[1]);
        status.setUnlikeTimes(next[2]);
        status.setCommentTimes(next[3]);
        status.setCoinTimes(next[4]);
        status.setShareTimes(next[5]);
        status.setCollectTimes(next[6]);
        status.setDanmuTimes(next[7]);
    }

    private static VideoStatus copy(VideoStatus status) {
        return new VideoStatus(
                status.getVid(),
                status.getPlayTimes(),
                status.getLikeTimes(),
                status.getUnlikeTimes(),
                status.getCommentTimes(),
                status.getCoinTimes(),
                status.getShareTimes(),
                status.getCollectTimes(),
                status.getDanmuTimes()
        );
    }

    /** 可控时钟：测试直接推进毫秒，不 sleep。 */
    static final class MutableClock extends Clock {

        private final AtomicLong millis =
                new AtomicLong(System.currentTimeMillis());

        void advance(long deltaMillis) {
            millis.addAndGet(deltaMillis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }
}
