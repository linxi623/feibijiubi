package com.feibijiubi.backend.videostats.mq;

import com.feibijiubi.backend.infrastructure.redis.RedisOperationException;
import com.feibijiubi.backend.videostats.exception.RetryableMessageException;
import com.feibijiubi.backend.videostats.exception.VideoStatusFlushDataException;
import com.feibijiubi.backend.videostats.exception.VideoStatusFlushDeferredException;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.config.VideoStatusSchedulingConfig;
import com.feibijiubi.backend.videostats.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.videostats.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.videostats.service.VideoStatusBatchFlushService;
import com.feibijiubi.backend.videostats.service.VideoStatusVidMutex;
import com.feibijiubi.backend.infrastructure.redis.RedisKeyUtils;
import com.feibijiubi.backend.infrastructure.redis.operation.RedisSetOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class VideoStatusBatchFlushScheduler {

    /**
     * T03 延后重试的常量退避：落在 1s..30s 区间内，先固定常量，
     * 后续 T04/T05 再改成可配置项。常量只影响“多久重试一次”，不影响正确性。
     */
    static final long DEFER_BACKOFF_MILLIS = 2_000L;

    /** 同一 vid 连续延后达到该次数后只升级为告警日志，仍然不隔离、不改写统计。 */
    static final int DEFER_ALERT_BUDGET = 30;

    /** 超过预算后每隔这么多次再打一条 ERROR，避免每 2 秒刷一条日志。 */
    private static final int DEFER_ALERT_REPEAT = 30;

    /** 本地退避表的硬上限：LRU 淘汰只影响效率（提前重试），不影响正确性。 */
    static final int MAX_DEFERRAL_ENTRIES = 4096;

    private final VideoStatusProperties properties;
    private final RedisSetOperations redisSetOperations;
    private final VideoStatusBatchFlushService batchFlushService;
    private final VideoStatusConsumedEventMapper consumedEventMapper;
    private final VideoStatusVidMutex vidMutex;
    private final Clock clock;

    /**
     * T05 只读观测：允许为 null（纯构造测试/未装配指标的隔离配置）。
     * 指标失败不得改变 dirty/退避/修复分类。
     */
    @Autowired(required = false)
    private VideoStatusTelemetry telemetry;

    /**
     * 每个 vid 的本地退避状态。只存在于当前实例内存：
     * 重启/淘汰/多实例都会让退避“忘记”，代价只是提前再试一次，
     * 因为 dirty 标记与 status 1 事件始终保留在 Redis/MySQL 中。
     */
    private final Map<Integer, DeferralState> deferrals =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<Integer, DeferralState> eldest
                        ) {
                            return size() > MAX_DEFERRAL_ENTRIES;
                        }
                    }
            );

    @Autowired
    public VideoStatusBatchFlushScheduler(
            VideoStatusProperties properties,
            RedisSetOperations redisSetOperations,
            VideoStatusBatchFlushService batchFlushService,
            VideoStatusConsumedEventMapper consumedEventMapper,
            VideoStatusVidMutex vidMutex
    ) {
        this(
                properties,
                redisSetOperations,
                batchFlushService,
                consumedEventMapper,
                vidMutex,
                Clock.systemUTC()
        );
    }

    /**
     * 测试注入可控时钟：延后退避用 {@link Clock#millis()} 判定，
     * 测试可直接推进时间，不靠 sleep 碰运气。
     */
    public VideoStatusBatchFlushScheduler(
            VideoStatusProperties properties,
            RedisSetOperations redisSetOperations,
            VideoStatusBatchFlushService batchFlushService,
            VideoStatusConsumedEventMapper consumedEventMapper,
            VideoStatusVidMutex vidMutex,
            Clock clock
    ) {
        this.properties = properties;
        this.redisSetOperations = redisSetOperations;
        this.batchFlushService = batchFlushService;
        this.consumedEventMapper = consumedEventMapper;
        this.vidMutex = vidMutex;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.video-status.flush-fixed-delay-ms:500}",
            scheduler = VideoStatusSchedulingConfig.FLUSH_SCHEDULER_BEAN
    )
    public void flushDirtyVideos() {
        if (!properties.isAsyncEnabled()
                || !properties.isSchedulingEnabled()) {
            return;
        }

        heartbeat(VideoStatusTelemetry.Operation.FLUSH_TICK);
        Set<String> dirtyVids = redisSetOperations.pop(
                RedisKeyUtils.dirtyVideo(),
                properties.getFlushDirtyBatchSize()
        );
        if (dirtyVids == null || dirtyVids.isEmpty()) {
            return;
        }

        long startedAt = System.nanoTime();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(
                properties.getRoundBudgetMs()
        );
        Iterator<String> iterator = dirtyVids.iterator();

        while (iterator.hasNext()) {
            String rawVid = iterator.next();

            // T06：预算检查放在“处理下一条之前”。已经开始的 vid 不会被强杀：
            // 它由 100ms 锁等待 + 数据库事务/SQL 超时各自界定。
            if (System.nanoTime() - startedAt >= budgetNanos) {
                int requeued = requeueDirty(rawVid) + requeueAll(iterator);
                log.warn(
                        "视频统计刷库达到单轮预算，已把未处理 dirty 全部补回，"
                                + "留待下一轮: budgetMs={}, requeued={}",
                        properties.getRoundBudgetMs(),
                        requeued
                );
                return;
            }

            Integer vid = parseVid(rawVid);
            if (vid == null) {
                log.error("dirty Set 包含非法 vid，已忽略: {}", rawVid);
                continue;
            }
            if (isBackingOff(vid)) {
                // 退避未到期：不取 vid 锁、不查 SQL，只把 dirty 放回去，
                // 避免“每 500ms 锁 + SELECT ... FOR UPDATE”的热循环。
                requeueDirty(vid);
                continue;
            }
            flushOne(vid);
        }
    }

    /**
     * 单轮预算用尽时把剩余 dirty 原样补回。绝不 SREM：
     * 否则会误删并发消费者刚 SADD 的新标记，导致“无人落库”。
     */
    private int requeueAll(Iterator<String> remaining) {
        int requeued = 0;
        while (remaining.hasNext()) {
            requeued += requeueDirty(remaining.next());
        }
        return requeued;
    }

    private int requeueDirty(String rawVid) {
        Integer vid = parseVid(rawVid);
        if (vid == null) {
            log.error("dirty Set 包含非法 vid，已忽略: {}", rawVid);
            return 0;
        }
        requeueDirty(vid);
        return 1;
    }

    private void flushOne(Integer vid) {
        long startedAt = System.nanoTime();
        try {
            // T06：调度轮内只等 100ms，不让一个被占用的 vid 吃掉整轮预算；
            // 消费/重建路径仍用 VidMutex 默认的 10 秒等待，语义不变。
            vidMutex.withLock(
                    vid,
                    VideoStatusVidMutex.SCHEDULER_WAIT_TIME_MILLIS,
                    () -> flushLocked(vid)
            );
            recordFlush(VideoStatusTelemetry.Outcome.SUCCESS, startedAt);
        } catch (VideoStatusFlushDeferredException e) {
            recordFlush(VideoStatusTelemetry.Outcome.DEFERRED, startedAt);
            defer(vid, e);
        } catch (VideoStatusFlushDataException e) {
            recordFlush(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            handleDataError(e);
        } catch (DataAccessException
                 | RetryableMessageException
                 | RedisOperationException e) {
            recordFlush(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            requeueDirty(vid);
            log.warn("视频统计批量刷库暂时失败，将重试: vid={}", vid, e);
        } catch (Exception e) {
            recordFlush(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            requeueDirty(vid);
            log.error("视频统计批量刷库发生未知异常，将保留 dirty: vid={}", vid, e);
        }
    }

    private void heartbeat(VideoStatusTelemetry.Operation operation) {
        VideoStatusTelemetry target = telemetry;
        if (target == null) {
            return;
        }
        try {
            target.heartbeat(operation);
        } catch (RuntimeException ignored) {
            // 心跳失败不影响调度。
        }
    }

    private void recordFlush(
            VideoStatusTelemetry.Outcome outcome,
            long startedAt
    ) {
        VideoStatusTelemetry target = telemetry;
        if (target == null) {
            return;
        }
        try {
            target.record(
                    VideoStatusTelemetry.Operation.FLUSH,
                    outcome,
                    System.nanoTime() - startedAt
            );
        } catch (RuntimeException ignored) {
            // 指标自身失败不得改变退避/修复/重试分类。
        }
    }

    private void flushLocked(Integer vid) {
        batchFlushService.flushOneVideo(
                vid,
                properties.getFlushEventBatchSize()
        );
        // 正常刷库成功：清空该 vid 的退避，下一次延后重新从预算开始计数。
        deferrals.remove(vid);
        refreshDirtyMembership(vid);
    }

    /**
     * 暂态延后：保留 dirty 标记、保留 status 1 事件、不改写统计，
     * 只记录退避时间与次数；超过预算只升级日志告警，绝不转人工修复。
     */
    private void defer(Integer vid, VideoStatusFlushDeferredException error) {
        DeferralState state = deferrals.computeIfAbsent(
                vid,
                key -> new DeferralState()
        );
        state.attempts++;
        state.nextAttemptAtMillis = clock.millis() + DEFER_BACKOFF_MILLIS;
        // 注意：defer 时绝不 SREM dirty，也绝不 markFlushRepairRequired。
        requeueDirty(vid);

        if (state.attempts <= DEFER_ALERT_BUDGET) {
            log.warn(
                    "视频统计刷库延后，等待依赖事件落库: vid={}, attempts={}, reason={}",
                    vid,
                    state.attempts,
                    error.getReason()
            );
            return;
        }
        if ((state.attempts - DEFER_ALERT_BUDGET) % DEFER_ALERT_REPEAT == 0) {
            log.error(
                    "视频统计刷库延后已超过告警预算，事件仍保留待刷（不隔离、不篡改）: "
                            + "vid={}, attempts={}, ids={}, reason={}",
                    vid,
                    state.attempts,
                    error.getConsumedEventIds(),
                    error.getReason()
            );
        }
    }

    private boolean isBackingOff(Integer vid) {
        DeferralState state = deferrals.get(vid);
        return state != null && clock.millis() < state.nextAttemptAtMillis;
    }

    private void handleDataError(VideoStatusFlushDataException error) {
        Integer vid = error.getVid();
        try {
            batchFlushService.markRepairRequired(
                    error.getConsumedEventIds(),
                    error.getErrorSummary()
            );
            deferrals.remove(vid);
            refreshDirtyMembership(vid);
            log.error(
                    "视频统计刷库数据异常，事件已转人工修复: vid={}, ids={}",
                    vid,
                    error.getConsumedEventIds(),
                    error
            );
        } catch (Exception markError) {
            requeueDirty(vid);
            log.error(
                    "视频统计刷库数据异常且转人工修复失败: vid={}",
                    vid,
                    markError
            );
        }
    }

    private void refreshDirtyMembership(Integer vid) {
        if (consumedEventMapper.countPendingByVid(vid) > 0) {
            requeueDirty(vid);
        } else {
            // 该 vid 已无待刷事件：本地退避状态没有意义，及时释放内存。
            deferrals.remove(vid);
        }
        // 当前 vid 已由 SPOP 取出。无待刷事件时无需 SREM：
        // 否则会误删 count 查询之后，无锁消费者刚刚 SADD 的新标记。
        // 即使残留一次空调度，也好过丢失新事件的唤醒。
    }

    private void requeueDirty(Integer vid) {
        try {
            redisSetOperations.add(
                    RedisKeyUtils.dirtyVideo(),
                    String.valueOf(vid)
            );
        } catch (Exception e) {
            log.error("重新加入 dirty Set 失败: vid={}", vid, e);
        }
    }

    private Integer parseVid(String rawVid) {
        try {
            int vid = Integer.parseInt(rawVid);
            return vid > 0 ? vid : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class DeferralState {
        private int attempts;
        private long nextAttemptAtMillis;
    }
}
