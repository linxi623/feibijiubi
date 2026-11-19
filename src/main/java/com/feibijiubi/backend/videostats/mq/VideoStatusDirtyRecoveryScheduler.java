package com.feibijiubi.backend.videostats.mq;

import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.config.VideoStatusSchedulingConfig;
import com.feibijiubi.backend.videostats.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.videostats.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.infrastructure.redis.RedisKeyUtils;
import com.feibijiubi.backend.infrastructure.redis.operation.RedisSetOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class VideoStatusDirtyRecoveryScheduler {

    /**
     * T11 失败 vid 的本地退避常量。只影响“多久再补一次 dirty”，
     * 不影响待刷事件（status 1）和 dirty 标记的持久语义。
     */
    static final long RECOVERY_BACKOFF_MILLIS = 2_000L;

    /** 本地退避表的硬上限：LRU 淘汰只影响效率（提前重试），不影响正确性。 */
    static final int MAX_BACKOFF_ENTRIES = 4096;

    private final VideoStatusProperties properties;
    private final VideoStatusConsumedEventMapper consumedEventMapper;
    private final RedisSetOperations redisSetOperations;
    private final Clock clock;

    /**
     * T05 只读观测：允许为 null（纯构造测试/未装配指标的隔离配置）。
     * 心跳只证明“恢复调度还在跑”，不参与 dirty 重加逻辑。
     */
    @Autowired(required = false)
    private VideoStatusTelemetry telemetry;

    /**
     * T11 跨轮 keyset 游标：上一轮已扫描到的最大 vid。
     * 只存在当前实例内存，重启/多实例丢失只影响效率，不影响正确性：
     * 下一轮从头开始仍会通过回绕覆盖全部待刷 vid。
     */
    private Integer cursor;

    /**
     * 每个失败 vid 的本地退避截止时间。无界失败不新增无界状态：
     * 超过 {@link #MAX_BACKOFF_ENTRIES} 用 LRU 淘汰，代价只是提前重试一次。
     */
    private final Map<Integer, Long> failureBackoffUntilMillis =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<Integer, Long> eldest
                        ) {
                            return size() > MAX_BACKOFF_ENTRIES;
                        }
                    }
            );

    @Autowired
    public VideoStatusDirtyRecoveryScheduler(
            VideoStatusProperties properties,
            VideoStatusConsumedEventMapper consumedEventMapper,
            RedisSetOperations redisSetOperations
    ) {
        this(properties, consumedEventMapper, redisSetOperations, Clock.systemUTC());
    }

    /** 测试注入可控时钟，退避判定不依赖 sleep 碰运气。 */
    public VideoStatusDirtyRecoveryScheduler(
            VideoStatusProperties properties,
            VideoStatusConsumedEventMapper consumedEventMapper,
            RedisSetOperations redisSetOperations,
            Clock clock
    ) {
        this.properties = properties;
        this.consumedEventMapper = consumedEventMapper;
        this.redisSetOperations = redisSetOperations;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString =
                    "${app.video-status.flush-recovery-fixed-delay-ms:5000}",
            scheduler = VideoStatusSchedulingConfig.RECOVERY_SCHEDULER_BEAN
    )
    public void recoverDirtyVids() {
        if (!properties.isAsyncEnabled()
                || !properties.isSchedulingEnabled()) {
            return;
        }

        heartbeat(VideoStatusTelemetry.Operation.RECOVERY_TICK);
        List<Integer> vids = selectNextBatch();
        if (vids == null || vids.isEmpty()) {
            return;
        }

        long startedAt = System.nanoTime();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(
                properties.getRoundBudgetMs()
        );
        int recovered = 0;
        Integer lastProcessed = null;

        for (Integer vid : vids) {
            // T06：预算用尽就结束本轮。只推进到“已处理”的 vid，
            // 同批剩余 vid 下一轮会从游标之后继续，不会因为整批推进而饿死。
            if (System.nanoTime() - startedAt >= budgetNanos) {
                // 本轮一条都没处理时不能把游标写成 null：否则上一轮已经推进的
                // keyset 进度会被丢弃，下一轮又从最小 vid 重扫，极端情况下
                // （预算极小/首条调用极慢）会反复卡在头部，饿死后面的视频。
                if (lastProcessed != null) {
                    cursor = lastProcessed;
                }
                log.warn(
                        "视频统计 dirty 恢复达到单轮预算，本轮已补回 {} 个，"
                                + "剩余 vid 留在数据库等下一轮反哺: budgetMs={}",
                        recovered,
                        properties.getRoundBudgetMs()
                );
                return;
            }
            if (vid == null) {
                continue;
            }
            lastProcessed = vid;
            if (isBackingOff(vid)) {
                // 失败退避未到期：不重复 SADD，避免失败 vid 每轮热循环。
                // 待刷事件仍在 MySQL，dirty 的最终恢复不依赖这一次尝试。
                continue;
            }
            try {
                redisSetOperations.add(
                        RedisKeyUtils.dirtyVideo(),
                        String.valueOf(vid)
                );
                failureBackoffUntilMillis.remove(vid);
                recovered++;
            } catch (Exception e) {
                recordFailure(vid);
                log.warn("恢复 dirty vid 失败，进入本地退避: vid={}", vid, e);
            }
        }
        if (lastProcessed != null) {
            cursor = lastProcessed;
        }
    }

    /**
     * 稳定 keyset 分页：优先取游标之后的 vid；没有更多时从头回绕。
     * 回绕只重置本地游标，不删除 dirty、不修改事件状态。
     */
    private List<Integer> selectNextBatch() {
        int limit = properties.getFlushDirtyBatchSize();
        List<Integer> vids = consumedEventMapper.selectPendingVidsAfter(cursor, limit);
        if (vids != null && !vids.isEmpty()) {
            return vids;
        }
        if (cursor == null) {
            return vids;
        }
        // 到达末尾：下一轮从最小 vid 重新开始。即使此刻没有待刷 vid，
        // 回绕查询也为空，不会无限扫描；新事件仍由消费者 SADD 唤醒。
        cursor = null;
        return consumedEventMapper.selectPendingVidsAfter(null, limit);
    }

    private boolean isBackingOff(Integer vid) {
        Long until = failureBackoffUntilMillis.get(vid);
        if (until == null) {
            return false;
        }
        if (clock.millis() < until) {
            return true;
        }
        failureBackoffUntilMillis.remove(vid);
        return false;
    }

    private void recordFailure(Integer vid) {
        failureBackoffUntilMillis.put(
                vid,
                clock.millis() + RECOVERY_BACKOFF_MILLIS
        );
    }

    private void heartbeat(VideoStatusTelemetry.Operation operation) {
        VideoStatusTelemetry target = telemetry;
        if (target == null) {
            return;
        }
        try {
            target.heartbeat(operation);
        } catch (RuntimeException ignored) {
            // 心跳失败不影响恢复调度。
        }
    }
}
