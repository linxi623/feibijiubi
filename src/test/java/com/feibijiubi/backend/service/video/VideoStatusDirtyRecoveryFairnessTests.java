package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mq.VideoStatusDirtyRecoveryScheduler;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisSetOperations;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T11：dirty 恢复必须用稳定 keyset 游标跨轮推进，而不是永远扫描最小 vid 的固定 LIMIT。
 *
 * <p>核心场景来自计划验收：250 个 pending 视频、批量 100，前 100 个持续失败，
 * 后 150 个丢失 dirty。keyset 游标必须在不超过 4 个完整恢复轮次内让 150 个健康视频
 * 都获得补回 dirty 的机会，同时保留失败视频的本地有界退避。</p>
 */
class VideoStatusDirtyRecoveryFairnessTests {

    private static final int BATCH = 100;

    @Test
    void failedHeadDoesNotStarveTailWithinFourRounds() {
        Set<Integer> pending = range(1, 250);
        Set<Integer> dirty = new TreeSet<>();
        Set<Integer> attempted = new TreeSet<>();
        VideoStatusConsumedEventMapper mapper = keysetMapper(pending);
        RedisSetOperations sets = mock(RedisSetOperations.class);
        doAnswer(call -> {
            int vid = vid(call.getArgument(1));
            attempted.add(vid);
            if (vid <= 100) {
                // 前 100 个持续失败：SADD 失败，dirty 保持丢失，事件仍是 status 1。
                throw new IllegalStateException("redis unavailable for head vid " + vid);
            }
            dirty.add(vid);
            return 1L;
        }).when(sets).add(eq(RedisKeyUtils.dirtyVideo()), any(String[].class));

        VideoStatusDirtyRecoveryScheduler scheduler = scheduler(mapper, sets);

        int rounds = 0;
        while (rounds < 4 && !dirty.containsAll(range(101, 250))) {
            scheduler.recoverDirtyVids();
            rounds++;
        }

        assertTrue(rounds <= 4, "healthy tail must be reached within 4 rounds, rounds=" + rounds);
        assertEquals(range(101, 250), dirty,
                "all 150 healthy vids must get a dirty-recovery opportunity");
        assertTrue(attempted.containsAll(range(1, 100)),
                "the failing head must also have had its first chance");
        verify(sets, never()).remove(anyString(), any(String[].class));
    }

    @Test
    void cursorWrapsAfterEndAndNewPendingVidIsPickedUp() {
        Set<Integer> pending = range(1, 250);
        Set<Integer> attempted = new TreeSet<>();
        VideoStatusConsumedEventMapper mapper = keysetMapper(pending);
        RedisSetOperations sets = mock(RedisSetOperations.class);
        doAnswer(call -> {
            attempted.add(vid(call.getArgument(1)));
            return 1L;
        }).when(sets).add(eq(RedisKeyUtils.dirtyVideo()), any(String[].class));

        VideoStatusDirtyRecoveryScheduler scheduler = scheduler(mapper, sets);
        for (int round = 0; round < 3; round++) {
            scheduler.recoverDirtyVids();
        }
        assertTrue(attempted.containsAll(pending), "first pass must cover the whole range");

        // 游标到达末尾后新增待刷视频：下一轮从 250 之后继续，不会饿死新 vid。
        pending.add(251);
        scheduler.recoverDirtyVids();
        assertTrue(attempted.contains(251), "new pending vid after the cursor must be scanned");

        // 模拟重启：新调度器游标为 null，从头开始扫描，低 vid 不会被漏掉。
        Set<Integer> restartedAttempts = new TreeSet<>();
        doAnswer(call -> {
            restartedAttempts.add(vid(call.getArgument(1)));
            return 1L;
        }).when(sets).add(eq(RedisKeyUtils.dirtyVideo()), any(String[].class));
        VideoStatusDirtyRecoveryScheduler restarted = scheduler(mapper, sets);
        restarted.recoverDirtyVids();

        assertEquals(range(1, 100), restartedAttempts,
                "restart with a lost cursor restarts from the minimum vid");
        verify(sets, never()).remove(anyString(), any(String[].class));
    }

    @Test
    void budgetStopsWithinBatchAndContinuesAfterLastProcessedVid() {
        Set<Integer> pending = new LinkedHashSet<>(List.of(10, 20, 30));
        Set<Integer> attempted = new TreeSet<>();
        VideoStatusConsumedEventMapper mapper = keysetMapper(pending);
        RedisSetOperations sets = mock(RedisSetOperations.class);
        doAnswer(call -> {
            attempted.add(vid(call.getArgument(1)));
            // 只让第一条有机会完成，制造“批内预算耗尽”。
            Thread.sleep(100L);
            return 1L;
        }).when(sets).add(eq(RedisKeyUtils.dirtyVideo()), any(String[].class));

        VideoStatusProperties properties = properties();
        properties.setRoundBudgetMs(50);
        VideoStatusDirtyRecoveryScheduler scheduler = new VideoStatusDirtyRecoveryScheduler(
                properties, mapper, sets, fixedClock()
        );

        scheduler.recoverDirtyVids();
        assertEquals(Set.of(10), attempted,
                "budget must stop before the second vid and cursor only advance to 10");

        properties.setRoundBudgetMs(5_000);
        scheduler.recoverDirtyVids();
        assertTrue(attempted.containsAll(pending),
                "the remaining vids in the same keyset page must not be skipped");
    }

    @Test
    void failedVidBacksOffThenRetriesAfterClockAdvance() {
        Set<Integer> pending = new TreeSet<>(List.of(10, 20, 30));
        Map<Integer, Integer> attempts = new TreeMap<>();
        VideoStatusConsumedEventMapper mapper = keysetMapper(pending);
        RedisSetOperations sets = mock(RedisSetOperations.class);
        doAnswer(call -> {
            int vid = vid(call.getArgument(1));
            attempts.merge(vid, 1, Integer::sum);
            if (vid == 10) {
                throw new IllegalStateException("redis unavailable for vid 10");
            }
            return 1L;
        }).when(sets).add(eq(RedisKeyUtils.dirtyVideo()), any(String[].class));

        MutableClock clock = new MutableClock();
        VideoStatusDirtyRecoveryScheduler scheduler = new VideoStatusDirtyRecoveryScheduler(
                properties(), mapper, sets, clock
        );

        scheduler.recoverDirtyVids();
        assertEquals(Map.of(10, 1, 20, 1, 30, 1), attempts,
                "a failed vid must not stop the rest of the batch");

        // 游标到末尾回绕；10 仍在 2 秒退避窗口内，不重复 SADD，20/30 不被跳过。
        scheduler.recoverDirtyVids();
        assertEquals(1, attempts.get(10).intValue(),
                "a backing-off vid must not be retried before its window expires");
        assertEquals(2, attempts.get(20).intValue());
        assertEquals(2, attempts.get(30).intValue());

        clock.advance(Duration.ofSeconds(3));
        scheduler.recoverDirtyVids();
        assertEquals(2, attempts.get(10).intValue(),
                "the failed vid must get another chance once backoff expires");
        verify(sets, never()).remove(anyString(), any(String[].class));
    }

    @Test
    void budgetExhaustedBeforeFirstVidDoesNotRewindCursor() {
        Set<Integer> pending = range(1, 250);
        Set<Integer> attempted = new TreeSet<>();
        AtomicBoolean slowNextPage = new AtomicBoolean(false);
        VideoStatusConsumedEventMapper mapper = mock(VideoStatusConsumedEventMapper.class);
        when(mapper.selectPendingVidsAfter(nullable(Integer.class), anyInt()))
                .thenAnswer(call -> {
                    Integer after = call.getArgument(0);
                    int limit = call.getArgument(1);
                    List<Integer> page = pending.stream()
                            .filter(vid -> after == null || vid > after)
                            .sorted()
                            .limit(limit)
                            .toList();
                    return slowNextPage.compareAndSet(true, false)
                            ? slowList(page)
                            : page;
                });
        RedisSetOperations sets = mock(RedisSetOperations.class);
        doAnswer(call -> {
            attempted.add(vid(call.getArgument(1)));
            return 1L;
        }).when(sets).add(eq(RedisKeyUtils.dirtyVideo()), any(String[].class));

        VideoStatusProperties properties = properties();
        VideoStatusDirtyRecoveryScheduler scheduler = new VideoStatusDirtyRecoveryScheduler(
                properties, mapper, sets, fixedClock()
        );

        scheduler.recoverDirtyVids();
        assertEquals(range(1, 100), attempted, "first round must advance the cursor to 100");

        attempted.clear();
        properties.setRoundBudgetMs(1);
        slowNextPage.set(true);
        scheduler.recoverDirtyVids();
        assertTrue(attempted.isEmpty(),
                "budget must expire before the first vid of the next page is processed");

        properties.setRoundBudgetMs(5_000);
        scheduler.recoverDirtyVids();
        assertTrue(attempted.contains(101),
                "the next round must continue after the last processed vid");
        assertFalse(attempted.contains(1),
                "a budget stop before the first vid must not rewind the cursor to null");
    }

    private VideoStatusDirtyRecoveryScheduler scheduler(
            VideoStatusConsumedEventMapper mapper,
            RedisSetOperations sets
    ) {
        return new VideoStatusDirtyRecoveryScheduler(
                properties(), mapper, sets, fixedClock()
        );
    }

    private VideoStatusProperties properties() {
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setAsyncEnabled(true);
        properties.setSchedulingEnabled(true);
        properties.setFlushDirtyBatchSize(BATCH);
        return properties;
    }

    private VideoStatusConsumedEventMapper keysetMapper(Set<Integer> pending) {
        VideoStatusConsumedEventMapper mapper = mock(VideoStatusConsumedEventMapper.class);
        when(mapper.selectPendingVidsAfter(nullable(Integer.class), anyInt()))
                .thenAnswer(call -> {
                    Integer after = call.getArgument(0);
                    int limit = call.getArgument(1);
                    return pending.stream()
                            .filter(vid -> after == null || vid > after)
                            .sorted()
                            .limit(limit)
                            .toList();
                });
        return mapper;
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC);
    }

    private static int vid(Object rawValue) {
        // Mockito 对 varargs 的 Answer 参数既可能是 String，也可能是 String[]。
        if (rawValue instanceof String[] values) {
            return Integer.parseInt(values[0]);
        }
        return Integer.parseInt((String) rawValue);
    }

    private static Set<Integer> range(int fromInclusive, int toInclusive) {
        Set<Integer> values = new TreeSet<>();
        for (int vid = fromInclusive; vid <= toInclusive; vid++) {
            values.add(vid);
        }
        return values;
    }

    /**
     * 让第一次迭代的 {@code next()} 真实消耗时间，从而在“处理第一条之前”触发预算检查。
     * 预算基于 {@link System#nanoTime()}，没有可注入时钟，只能用真实短睡眠制造该窗口。
     */
    private static List<Integer> slowList(List<Integer> values) {
        return new AbstractList<>() {
            @Override
            public Integer get(int index) {
                return values.get(index);
            }

            @Override
            public int size() {
                return values.size();
            }

            @Override
            public Iterator<Integer> iterator() {
                Iterator<Integer> delegate = values.iterator();
                return new Iterator<>() {
                    private boolean first = true;

                    @Override
                    public boolean hasNext() {
                        return delegate.hasNext();
                    }

                    @Override
                    public Integer next() {
                        if (first) {
                            first = false;
                            try {
                                Thread.sleep(20L);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return delegate.next();
                    }
                };
            }
        };
    }

    static final class MutableClock extends Clock {
        private final AtomicLong millis =
                new AtomicLong(Instant.parse("2026-10-22T00:00:00Z").toEpochMilli());

        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }
}
