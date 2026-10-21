package com.feibijiubi.backend.service.impl.video.videostatus;

import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.common.VideoStatusFlushDataException;
import com.feibijiubi.backend.common.VideoStatusFlushDeferredException;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.event.VideoStatusDelta;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class VideoStatusBatchFlushServiceImpl
        implements VideoStatusBatchFlushService {

    private static final int MAX_ERROR_LENGTH = 1000;

    /** 与 SQL 守卫一致的 Java 侧上界：0..Long.MAX_VALUE。 */
    private static final int COUNTER_FIELDS = 8;

    private final VideoStatusConsumedEventMapper consumedEventMapper;
    private final VideoStatusMapper videoStatusMapper;

    /**
     * T05 只读观测：允许为 null（纯构造测试/未装配指标的隔离配置）。
     * 只在事务提交后上报端到端延迟，指标失败不影响业务结果。
     */
    @Autowired(required = false)
    private VideoStatusTelemetry telemetry;

    /**
     * 取一个视频的一批数据进行刷库，如果没有刷库，就直接返回。
     *
     * <p>T03 错误分类（按“能否立即证明非法”划分）：</p>
     * <ul>
     *   <li>未知事件类型 → {@link VideoStatusFlushDataException}（受控修复）；</li>
     *   <li>批量更新影响 0 行时，先 {@code selectByVid} 分类：
     *       行缺失 → 受控修复；
     *       基线 + 增量越出 0..Long.MAX 任一界 → {@link VideoStatusFlushDeferredException}（延后重试）；
     *       其余（守卫通过却没命中行）→ {@link RetryableMessageException}（DB 暂时性重试）。</li>
     * </ul>
     *
     * <p>关键约束：单次落库越界（无论下界不足还是上界不足）都不能证明事件非法。
     * 下界不足：依赖的正增量可能已经写进 Redis，只是状态提交尚未完成；
     * 上界不足：依赖的取消/回退负增量可能已经写进 Redis，同样只是状态提交未完成。
     * 这两种情况都只能延后，绝不能把合法事件隔离成 status 3，也不能把统计改成 0。</p>
     *
     * <p>批次净增量本身超出 Long 范围（例如 {@code +MAX, -MAX, +1} 这类合法事件序列）
     * 同样只是“这一批无法一次落库”，应当延后等待拆批或相反增量落库，
     * 不能据此转人工修复。</p>
     *
     * <p>T03 公平前进（本方法内的一次有界备选，不做递归）：当首轮批次因
     * <b>上界不足</b>或<b>净量超出 long</b> 无法落库时，`selectPendingForUpdate` 的
     * “正增量优先”排序在 {@code limit = 1} 下会一直选中同一个 {@code +1}，
     * 导致合法的 {@code -1} 永远排不上队、批次永不收敛。
     * 因此允许在<b>同一事务、同一 vid 锁</b>内再做一次“负增量优先”候选查询：
     * 能刷就只刷它的 ids（初批事件保持 status 1，下一轮再试），
     * 仍是边界就延后。绝不通过修改原行状态来挪队列，也绝不无限递归。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void flushOneVideo(Integer vid, int limit) {
        if (vid == null || vid <= 0 || limit <= 0) {
            throw new IllegalArgumentException("刷库 vid 和 limit 不合法");
        }

        List<VideoStatusConsumedEvent> events =
                consumedEventMapper.selectPendingForUpdate(vid, limit);
        if (events.isEmpty()) {
            return;
        }

        List<Long> ids = idsOf(events);
        BatchOutcome outcome = tryFlushBatch(vid, ids, events);
        if (outcome == BatchOutcome.FLUSHED) {
            return;
        }

        flushNegativeFirstFallbackOnce(vid, limit, ids, outcome);
    }

    /**
     * 上限侧失败后的<b>唯一一次</b>“负增量优先”备选批次。
     *
     * <p>只处理 {@link BatchOutcome#UPPER_BOUND} / {@link BatchOutcome#NET_OVERFLOW}：
     * 下界不足需要的是正增量，换负增量优先没有意义，因此不查备选。</p>
     *
     * <p>备选批次里已经属于初批的 id 会被排除，避免对同一批事件重复尝试；
     * 若备选可刷，只推进备选 ids，初批保持 status 1（不修改原行状态挪队列）。
     * 备选仍是边界时抛延后异常，由调度器退避重试。</p>
     */
    private void flushNegativeFirstFallbackOnce(
            Integer vid,
            int limit,
            List<Long> initialIds,
            BatchOutcome initialOutcome
    ) {
        Set<Long> locked = new HashSet<>(initialIds);
        List<VideoStatusConsumedEvent> fallback = consumedEventMapper
                .selectPendingNegativeFirstForUpdate(vid, limit)
                .stream()
                .filter(event -> !locked.contains(event.getId()))
                .toList();
        if (fallback.isEmpty()) {
            throw new VideoStatusFlushDeferredException(
                    vid,
                    initialIds,
                    boundaryReason(initialOutcome)
            );
        }

        List<Long> fallbackIds = idsOf(fallback);
        BatchOutcome fallbackOutcome = tryFlushBatch(vid, fallbackIds, fallback);
        if (fallbackOutcome == BatchOutcome.FLUSHED) {
            return;
        }
        // 备选仍触达边界：延后，不递归再找第三批。
        throw new VideoStatusFlushDeferredException(
                vid,
                fallbackIds,
                boundaryReason(fallbackOutcome)
        );
    }

    /**
     * 尝试把给定批次一次落库。
     *
     * <p>返回 {@link BatchOutcome#UPPER_BOUND} / {@link BatchOutcome#NET_OVERFLOW}
     * 表示“这一批无法一次落库但事件仍可能合法”，由调用方决定是否走一次备选；
     * 其余无法立即证明非法的情况继续抛异常（延后/重试/受控修复分类不变）。</p>
     */
    private BatchOutcome tryFlushBatch(
            Integer vid,
            List<Long> ids,
            List<VideoStatusConsumedEvent> events
    ) {
        VideoStatusDelta delta = sumDelta(vid, ids, events);
        if (delta == null) {
            return BatchOutcome.NET_OVERFLOW;
        }

        if (delta.isZero()) {
            // 净零批次不需要写统计，但仍要推进事件状态（T02 验收要求）。
            markFlushed(vid, events);
            return BatchOutcome.FLUSHED;
        }

        if (videoStatusMapper.applyBatchDelta(vid, delta) == 1) {
            markFlushed(vid, events);
            return BatchOutcome.FLUSHED;
        }

        return classifyZeroRowUpdate(vid, ids, delta);
    }

    private List<Long> idsOf(List<VideoStatusConsumedEvent> events) {
        return events.stream()
                .map(VideoStatusConsumedEvent::getId)
                .toList();
    }

    private String boundaryReason(BatchOutcome outcome) {
        return outcome == BatchOutcome.NET_OVERFLOW
                ? "批次净增量超出 Long 范围，等待拆批或相反增量落库后重试"
                : "净增量使统计超过 Long 上界，等待依赖的负增量落库后重试";
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void markRepairRequired(
            List<Long> consumedEventIds,
            String lastError
    ) {
        if (consumedEventIds == null || consumedEventIds.isEmpty()) {
            return;
        }
        int updated = consumedEventMapper.markFlushRepairRequired(
                consumedEventIds,
                truncate(lastError)
        );
        if (updated != consumedEventIds.size()) {
            throw new RetryableMessageException(
                    "刷库异常事件转人工修复数量不一致"
            );
        }
    }

    /**
     * 按字段用 {@link BigInteger} 累计批次净增量，最后一次性收窄到 {@code long}。
     *
     * <p>不能用 {@code Math.addExact} 逐步累加：事件顺序是“正增量优先”，
     * {@code +Long.MAX_VALUE, -Long.MAX_VALUE, +1} 这类完全合法的序列会在中间步溢出，
     * 从而把合法事件误判成永久非法。中间值无上限、只要求<b>最终净量</b>落在 long 内。</p>
     *
     * <p>最终净量仍超出 long 时，只是“这一批无法一次落库”（例如 {@code +MAX, +MAX}），
     * 应延后等待拆批或相反增量，不允许转人工修复。</p>
     *
     * @return 批次净增量；返回 {@code null} 表示最终净量超出 long（无法一次落库）
     */
    private VideoStatusDelta sumDelta(
            Integer vid,
            List<Long> ids,
            List<VideoStatusConsumedEvent> events
    ) {
        BigInteger[] sums = new BigInteger[COUNTER_FIELDS];
        Arrays.fill(sums, BigInteger.ZERO);
        try {
            for (VideoStatusConsumedEvent event : events) {
                long[] values = deltaValues(VideoStatusDelta.from(event));
                for (int i = 0; i < COUNTER_FIELDS; i++) {
                    sums[i] = sums[i].add(BigInteger.valueOf(values[i]));
                }
            }
        } catch (IllegalArgumentException e) {
            // 未知统计类型（枚举解析失败）：唯一能立即证明非法的情形。
            throw new VideoStatusFlushDataException(
                    vid,
                    ids,
                    "消费事件包含未知统计类型，需人工修复"
            );
        }

        long[] totals = new long[COUNTER_FIELDS];
        try {
            for (int i = 0; i < COUNTER_FIELDS; i++) {
                totals[i] = sums[i].longValueExact();
            }
        } catch (ArithmeticException e) {
            // 最终净量超出 long：无法一次落库，但事件本身仍可能是合法的。
            // 交由调用方先尝试一次负增量优先备选，再决定延后。
            return null;
        }
        return new VideoStatusDelta(
                totals[0],
                totals[1],
                totals[2],
                totals[3],
                totals[4],
                totals[5],
                totals[6],
                totals[7]
        );
    }

    private void markFlushed(
            Integer vid,
            List<VideoStatusConsumedEvent> events
    ) {
        List<Long> ids = idsOf(events);
        if (consumedEventMapper.markFlushed(ids) != ids.size()) {
            throw new RetryableMessageException(
                    "事件 FLUSHED 数量与锁定数量不一致，vid=" + vid
            );
        }
        recordFlushedLatencyAfterCommit(events);
    }

    /**
     * T05：事件创建→FLUSHED 的端到端延迟只在事务真正提交后上报。
     *
     * <p>用 {@code afterCommit} 而不是提交前上报，是为了让回滚的批次不产生“成功”样本：
     * 事务回滚时 Spring 不会调用 afterCommit。payload 由指标实现自行解析，
     * 解析失败/指标自身异常一律吞掉，绝不改变已确定的业务结果。</p>
     */
    private void recordFlushedLatencyAfterCommit(
            List<VideoStatusConsumedEvent> events
    ) {
        VideoStatusTelemetry target = telemetry;
        if (target == null || events.isEmpty()) {
            return;
        }
        try {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                // 无事务上下文（如纯单测直调）时不上报，避免把未提交的落库记成成功。
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            for (VideoStatusConsumedEvent event : events) {
                                String payload = event.getPayload();
                                if (payload == null) {
                                    continue;
                                }
                                try {
                                    target.recordFlushedPayload(payload);
                                } catch (RuntimeException ignored) {
                                    // 坏 payload / 指标自身失败不得影响已提交业务。
                                }
                            }
                        }
                    }
            );
        } catch (RuntimeException ignored) {
            // 注册观测失败不改变落库结果。
        }
    }

    /**
     * {@code applyBatchDelta} 返回 0 行时按实际数据分类，不做“一次 0 行就隔离”的猜测。
     *
     * <p>本方法要么返回 {@link BatchOutcome#UPPER_BOUND}（调用方先尝试一次负增量优先备选），
     * 要么抛异常：延后重试或转受控修复，不存在“静默跳过”的分支。</p>
     */
    private BatchOutcome classifyZeroRowUpdate(
            Integer vid,
            List<Long> ids,
            VideoStatusDelta delta
    ) {
        VideoStatus current = videoStatusMapper.selectByVid(vid);
        if (current == null) {
            throw new VideoStatusFlushDataException(
                    vid,
                    ids,
                    "视频统计行缺失，无法落库，需人工修复"
            );
        }

        long[] base = counterValues(current);
        long[] increment = deltaValues(delta);
        boolean negative = false;
        boolean overflow = false;
        for (int i = 0; i < COUNTER_FIELDS; i++) {
            long projected;
            try {
                projected = Math.addExact(base[i], increment[i]);
            } catch (ArithmeticException e) {
                overflow = true;
                continue;
            }
            if (projected < 0) {
                negative = true;
            }
        }

        if (overflow) {
            // 上界不足与下界不足同理：依赖的取消/回退负增量可能已写 Redis，
            // 只是 markRedisApplied 状态提交尚未完成，flush 暂时看不到它。
            // 一次上界不足不能证明事件永久非法：先让调用方试一次负增量优先备选。
            return BatchOutcome.UPPER_BOUND;
        }
        if (negative) {
            // 依赖事件可能已写 Redis 但状态提交未完成：延后重试，不隔离、不篡改为 0。
            throw new VideoStatusFlushDeferredException(
                    vid,
                    ids,
                    "净增量包含当前基线不足的负值，等待依赖事件落库后重试"
            );
        }

        // 守卫在 Java 侧成立、SQL 却未命中行：视为暂态（并发/可见性），交给重试。
        throw new RetryableMessageException(
                "视频统计批量更新未命中行，将重试: vid=" + vid
        );
    }

    /** 单批落库结果：只有上限侧两种边界允许再试一次负增量优先备选。 */
    private enum BatchOutcome {
        FLUSHED,
        NET_OVERFLOW,
        UPPER_BOUND
    }

    private long[] counterValues(VideoStatus status) {
        return new long[]{
                valueOrZero(status.getPlayTimes()),
                valueOrZero(status.getLikeTimes()),
                valueOrZero(status.getUnlikeTimes()),
                valueOrZero(status.getCommentTimes()),
                valueOrZero(status.getCoinTimes()),
                valueOrZero(status.getShareTimes()),
                valueOrZero(status.getCollectTimes()),
                valueOrZero(status.getDanmuTimes())
        };
    }

    private long[] deltaValues(VideoStatusDelta delta) {
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

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private String truncate(String message) {
        String value = message == null || message.isBlank()
                ? "unknown flush data error"
                : message;
        return value.length() <= MAX_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_LENGTH);
    }
}
