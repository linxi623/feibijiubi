package com.feibijiubi.backend.videostats.entity;

import lombok.Data;

/**
 * T10 业务关系真值的有界聚合结果。
 *
 * <p>SQL 先对 {@code user_video} 按 vid 取最多 {@code cap} 行，再在派生表上聚合，
 * 因此不会对单个 vid 的全部关系做无界 SUM。{@code sampledRows == cap} 时调用方
 * 必须按“可能被截断”处理，不能把这里的结果当作完整真值。</p>
 */
@Data
public class VideoStatusReconciliationRelationAggregate {
    private Long sampledRows;
    private Long likedRows;
    private Long unlikedRows;
    private Long coinSum;
    private Long collectRows;
}
