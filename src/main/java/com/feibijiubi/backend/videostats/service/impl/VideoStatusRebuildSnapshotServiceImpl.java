package com.feibijiubi.backend.videostats.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.videostats.exception.RetryableMessageException;
import com.feibijiubi.backend.videostats.support.VideoStatusCounterRange;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.entity.VideoStatus;
import com.feibijiubi.backend.videostats.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusDelta;
import com.feibijiubi.backend.videostats.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.videostats.mapper.VideoStatusMapper;
import com.feibijiubi.backend.videostats.service.VideoStatusRebuildSnapshot;
import com.feibijiubi.backend.videostats.service.VideoStatusRebuildSnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * MySQL 快照读取。
 *
 * <p>T12-B：候选改为 <b>同一事务内</b> 的 keyset 分页读取。一致性来自“一个
 * REPEATABLE_READ 只读事务”：MySQL 的一致性快照在事务内第一次一致性读（这里的基线
 * {@code selectByVid}）时建立，之后每一页 {@code id > afterId} 都读同一个快照，
 * 因此不会出现“第一页读到的事件在第二页被并发刷库删掉”、也不会把分页之后才登记的
 * 新事件混进来。禁止把分页搬到事务外（跨事务分页没有统一水位，结果可重复/可漏）。</p>
 *
 * <p>“有界”不等于常数内存：候选仍然是当前 DTO 的 list，上界由
 * {@code app.video-status.rebuild-max-candidates} 显式给出（默认 100000）。
 * 超过上界抛 {@link RetryableMessageException}，绝不返回截断快照——截断会让重建
 * 少算已确认事件、把 Redis current 写成偏小值。内存上界是
 * {@code rebuild-max-candidates + 1} 条候选（发现第 N+1 条时立即失败）。</p>
 *
 * <p>快照读取预算用 {@link System#nanoTime()} 计量，在“取下一页之前”和“转换下一条之前”
 * 检查；超预算同样抛 {@link RetryableMessageException}。它只是放弃本次读取，
 * <b>不会</b>硬中断已经在执行的 SQL，也不保证 SQL 本身在预算内返回。</p>
 */
@Service
public class VideoStatusRebuildSnapshotServiceImpl
        implements VideoStatusRebuildSnapshotService {

    private final VideoStatusMapper videoStatusMapper;
    private final VideoStatusConsumedEventMapper consumedEventMapper;
    private final ObjectMapper objectMapper;
    private final VideoStatusProperties properties;
    /** 可注入的单调时钟，仅用于快照读取预算，不参与任何业务判断。 */
    private final LongSupplier nanoTime;

    @Autowired
    public VideoStatusRebuildSnapshotServiceImpl(
            VideoStatusMapper videoStatusMapper,
            VideoStatusConsumedEventMapper consumedEventMapper,
            ObjectMapper objectMapper,
            VideoStatusProperties properties
    ) {
        this(videoStatusMapper, consumedEventMapper, objectMapper, properties,
                System::nanoTime);
    }

    /** 测试构造：注入确定性单调时钟，不改变生产默认的 System.nanoTime。 */
    public VideoStatusRebuildSnapshotServiceImpl(
            VideoStatusMapper videoStatusMapper,
            VideoStatusConsumedEventMapper consumedEventMapper,
            ObjectMapper objectMapper,
            VideoStatusProperties properties,
            LongSupplier nanoTime
    ) {
        this.videoStatusMapper = videoStatusMapper;
        this.consumedEventMapper = consumedEventMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public VideoStatusRebuildSnapshot load(Integer vid) {
        validateVid(vid);

        // 与单轮调度预算同一写法：只比较“已用时长”和“预算”，不把起点加成一个可能溢出的
        // 绝对截止时间。
        long readStartNanos = nanoTime.getAsLong();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(
                properties.getRebuildSnapshotBudgetMs());

        VideoStatus baseline = videoStatusMapper.selectByVid(vid);
        if (baseline == null) {
            throw new RetryableMessageException(
                    "视频统计 MySQL 基线不存在，vid=" + vid
            );
        }

        List<VideoStatusRebuildSnapshot.Candidate> candidates =
                loadCandidates(vid, readStartNanos, budgetNanos);

        return new VideoStatusRebuildSnapshot(
                vid,
                baselineValue(baseline.getPlayTimes(), vid, "playTimes"),
                baselineValue(baseline.getLikeTimes(), vid, "likeTimes"),
                baselineValue(baseline.getUnlikeTimes(), vid, "unlikeTimes"),
                baselineValue(baseline.getCommentTimes(), vid, "commentTimes"),
                baselineValue(baseline.getCoinTimes(), vid, "coinTimes"),
                baselineValue(baseline.getShareTimes(), vid, "shareTimes"),
                baselineValue(baseline.getCollectTimes(), vid, "collectTimes"),
                baselineValue(baseline.getDanmuTimes(), vid, "danmuTimes"),
                candidates
        );
    }

    /**
     * 按 id 严格前进的 keyset 分页：每页最多 {@code rebuild-candidate-page-size} 行，
     * 读到空页或短页结束。三条硬边界：
     *
     * <ul>
     *   <li>页内每行 id 必须存在且严格递增：id 是唯一的 keyset 游标来源，缺失或乱序
     *       会让“读完了”与“还要继续”无法区分；</li>
     *   <li>游标必须严格前进（本页最后一条 id &gt; 上一页游标），否则立即失败，
     *       避免“某页不前进→同一批行被无限重复读”的假死循环；</li>
     *   <li>返回行数不得多于请求页大小，否则失败而不是悄悄接受；</li>
     *   <li>候选总数超过 {@code rebuild-max-candidates} 立即失败，不返回截断快照。</li>
     * </ul>
     */
    private List<VideoStatusRebuildSnapshot.Candidate> loadCandidates(
            Integer vid,
            long readStartNanos,
            long budgetNanos
    ) {
        int pageSize = properties.getRebuildCandidatePageSize();
        int maxCandidates = properties.getRebuildMaxCandidates();
        List<VideoStatusRebuildSnapshot.Candidate> candidates = new ArrayList<>();
        Long afterId = null;
        while (true) {
            requireBudget(vid, readStartNanos, budgetNanos, candidates.size());
            List<VideoStatusConsumedEvent> page =
                    consumedEventMapper.selectRebuildCandidatesAfter(
                            vid, afterId, pageSize
                    );
            if (page == null) {
                throw new RetryableMessageException(
                        "Redis 重建候选分页查询返回 null，vid=" + vid
                );
            }
            if (page.size() > pageSize) {
                throw new RetryableMessageException(
                        "Redis 重建候选分页返回行数超过页大小，vid=" + vid
                                + "，pageSize=" + pageSize
                                + "，actual=" + page.size()
                );
            }
            if (page.isEmpty()) {
                return candidates;
            }

            Long pageLastId = null;
            for (int rowIndex = 0; rowIndex < page.size(); rowIndex++) {
                VideoStatusConsumedEvent row = page.get(rowIndex);
                requireBudget(vid, readStartNanos, budgetNanos, candidates.size());
                Long rowId = row.getId();
                if (rowId == null) {
                    throw new RetryableMessageException(
                            "Redis 重建候选分页缺少 id 游标，vid=" + vid
                                    + "，页内位置=" + rowIndex
                    );
                }
                if (pageLastId != null && rowId <= pageLastId) {
                    throw new RetryableMessageException(
                            "Redis 重建候选分页 id 未严格递增，vid=" + vid
                                    + "，previousId=" + pageLastId
                                    + "，currentId=" + rowId
                    );
                }
                candidates.add(toCandidate(row));
                if (candidates.size() > maxCandidates) {
                    throw new RetryableMessageException(
                            "Redis 重建候选数超过上限，vid=" + vid
                                    + "，上限=" + maxCandidates
                                    + "（拒绝返回截断快照）"
                    );
                }
                pageLastId = rowId;
            }

            if (afterId != null && pageLastId <= afterId) {
                throw new RetryableMessageException(
                        "Redis 重建候选分页游标未前进，vid=" + vid
                                + "，afterId=" + afterId
                                + "，pageLastId=" + pageLastId
                );
            }
            if (page.size() < pageSize) {
                return candidates;
            }
            afterId = pageLastId;
        }
    }

    private void requireBudget(
            Integer vid,
            long readStartNanos,
            long budgetNanos,
            int loadedCandidates
    ) {
        if (nanoTime.getAsLong() - readStartNanos >= budgetNanos) {
            throw new RetryableMessageException(
                    "Redis 重建快照读取超出预算，vid=" + vid
                            + "，预算=" + properties.getRebuildSnapshotBudgetMs()
                            + " 毫秒，已转换候选数=" + loadedCandidates
                            + "（已发出的 SQL 不会被中断）"
            );
        }
    }

    private VideoStatusRebuildSnapshot.Candidate toCandidate(
            VideoStatusConsumedEvent row
    ) {
        if (row.getProcessStatus() == null
                || (row.getProcessStatus() != 0
                && row.getProcessStatus() != 1)) {
            throw new RetryableMessageException(
                    "Redis 重建候选事件状态非法，eventId=" + row.getEventId()
            );
        }
        if (row.getPayload() == null || row.getPayload().isBlank()) {
            throw new RetryableMessageException(
                    "Redis 重建候选事件缺少规范化 payload，eventId=" + row.getEventId()
            );
        }

        VideoStatusChangedEvent event;
        try {
            event = objectMapper.readValue(
                    row.getPayload(),
                    VideoStatusChangedEvent.class
            );
            event.validate();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new RetryableMessageException(
                    "Redis 重建候选事件 payload 非法，eventId=" + row.getEventId(),
                    e
            );
        }

        if (!Objects.equals(row.getEventId(), event.eventId())
                || !Objects.equals(row.getVid(), event.vid())
                || !Objects.equals(row.getEventType(), event.type().name())
                || !Objects.equals(row.getDelta(), event.delta())) {
            throw new RetryableMessageException(
                    "Redis 重建候选事件与 payload 不一致，eventId=" + row.getEventId()
            );
        }

        return new VideoStatusRebuildSnapshot.Candidate(
                event.eventId(),
                row.getProcessStatus(),
                VideoStatusDelta.from(event),
                event.hotScoreDelta()
        );
    }

    private void validateVid(Integer vid) {
        if (vid == null || vid <= 0) {
            throw new IllegalArgumentException("vid 必须大于 0");
        }
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * MySQL 基线直接进入重建累计，所以读取时就要守同一条非负契约：
     * 出现负数只可能是 schema 漂移或人工篡改，属于永久数据错误，
     * 不能悄悄流入累计再被当成“暂态溢出”无限重试。
     * （列本身是 BIGINT UNSIGNED，超过 Long.MAX_VALUE 的值在 JDBC 映射阶段就会失败，
     * 不会以负数形式抵达这里。）
     */
    private long baselineValue(Long value, Integer vid, String field) {
        return VideoStatusCounterRange.require(
                valueOrZero(value),
                "videoStatus[" + vid + "]." + field
        );
    }
}
