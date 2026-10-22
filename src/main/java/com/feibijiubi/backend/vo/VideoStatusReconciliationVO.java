package com.feibijiubi.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * T10 单视频只读事件对账结果。
 *
 * <p>这个 VO 只描述“同一请求内分别读到的观测值”，<b>不</b>给出 SAFE/收敛结论：</p>
 * <ul>
 *   <li>DB 八项计数、账本状态数量、事件窗口投影和 Redis 采样不是原子快照，
 *       并发写入可能让它们在时间上错位；</li>
 *   <li>账本/事件/关系查询都有 {@code limit + 1} 上界，被截断时只能得到下界；</li>
 *   <li>历史 ledger 总量不等于 DB 真值，Redis 采样也不能证明已收敛；</li>
 *   <li>接口绝不写 Redis/MySQL、不修复事件、不改变任何状态。</li>
 * </ul>
 */
@Data
@AllArgsConstructor
public class VideoStatusReconciliationVO {

    public static final String MODE = "READ_ONLY_DIAGNOSTIC";
    public static final String CONCURRENT_OBSERVATION_NOT_ATOMIC =
            "CONCURRENT_OBSERVATION_NOT_ATOMIC";
    public static final String VERDICT_NO_SAFE_CONCLUSION =
            "NO_SAFE_CONCLUSION_AUTOMATIC_REPAIR_DISABLED";

    private final Integer vid;
    private final Integer limit;
    private final String mode;
    private final boolean readOnly;
    private final boolean writePerformed;
    private final boolean automaticRepair;
    private final String observationBoundary;
    private final boolean incomplete;
    private final List<String> uncertainties;
    private final String verdict;
    private final DbCounts db;
    private final LedgerCounts ledger;
    private final EventProjection eventProjection;
    private final RelationTruth relations;
    private final RedisSample redis;
    private final String note;

    /** DB {@code video_status} 的八项计数；统计行缺失时 rowPresent=false 且计数为 null。 */
    @Data
    @AllArgsConstructor
    public static class DbCounts {
        private final boolean rowPresent;
        private final Long playTimes;
        private final Long likeTimes;
        private final Long unlikeTimes;
        private final Long commentTimes;
        private final Long coinTimes;
        private final Long shareTimes;
        private final Long collectTimes;
        private final Long danmuTimes;
        private final String note;
    }

    /**
     * 按 process_status 0/1/2/3 的有界账本数量。
     *
     * <p>每个 {@code statusXCapped=true} 表示该状态至少达到 {@code cap} 行，
     * 对应数量只是下界；{@code truncated} 是四个状态的兼容汇总。
     * 计数故意不做无界 COUNT：保守截断不是 bug，而是只读诊断的明确边界。</p>
     */
    @Data
    @AllArgsConstructor
    public static class LedgerCounts {
        private final int cap;
        private final long status0;
        private final long status1;
        private final long status2;
        private final long status3;
        private final boolean status0Capped;
        private final boolean status1Capped;
        private final boolean status2Capped;
        private final boolean status3Capped;
        private final boolean truncated;
        private final String note;
    }

    /**
     * 最近 {@code limit} 行安全事件的类型增量投影。
     * 查询最多读取 {@code limit + 1} 行，但第 {@code limit + 1} 行只用于判定截断，
     * 不计入 {@code rowCount}/{@code deltaByType}。类型 delta 用 BigInteger 汇总，
     * 避免 long 溢出；截断时只是窗口下界。
     */
    @Data
    @AllArgsConstructor
    public static class EventProjection {
        private final int requestedLimit;
        private final int rowCount;
        private final boolean truncated;
        private final boolean indexAvailable;
        private final Map<String, BigInteger> deltaByType;
        private final int unknownTypeCount;
        private final int nullDeltaCount;
        private final BigInteger unknownTypeDelta;
        private final Long minEventId;
        private final Long maxEventId;
        private final String note;
    }

    /**
     * {@code user_video} 业务关系的有界真值采样。
     * {@code truncated=true} 时 {@code usableForConclusion=false}，数值只是下界。
     */
    @Data
    @AllArgsConstructor
    public static class RelationTruth {
        private final boolean sampled;
        private final boolean truncated;
        private final boolean lowerBound;
        private final boolean usableForConclusion;
        private final long sampledRows;
        private final long likedRows;
        private final long unlikedRows;
        private final BigInteger coinSum;
        private final long collectRows;
        private final String note;
    }

    /**
     * Redis current Hash 的只读采样。未采样、字段缺失、非 canonical Long 或读取异常
     * 都保留为明确状态，所有计数为 null，绝不伪造 0。
     */
    @Data
    @AllArgsConstructor
    public static class RedisSample {
        private final String status;
        private final Long playTimes;
        private final Long likeTimes;
        private final Long unlikeTimes;
        private final Long commentTimes;
        private final Long coinTimes;
        private final Long shareTimes;
        private final Long collectTimes;
        private final Long danmuTimes;
        private final List<String> invalidFields;
        private final String note;
    }
}
