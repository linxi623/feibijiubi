package com.feibijiubi.backend.videostats.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 受审计重放的结果视图。
 *
 * <p>管理员必须能看到“动作是否真的执行了”以及“历史操作的 before/after”：</p>
 * <ul>
 *   <li>{@code replayApplied=true} 表示本次请求在同一事务里把 FAILED 重置为 PENDING
 *       并写入 {@code COMPLETED} 审计日志（before/after 同时持久化在
 *       {@code repair_log.last_error} 的 JSON 元数据里）；</li>
 *   <li>{@code duplicate=true} 表示同一 operationId + 相同 event/action/operator/reason 之前已完成，
 *       本次<b>没有</b>执行任何动作；before/after 从审计元数据原样重放；</li>
 *   <li>{@code current*} 是查询时的实时 outbox 状态，<b>不会</b>覆盖历史操作的 before/after。</li>
 * </ul>
 */
@Data
public class VideoStatusOutboxReplayVO {

    private String operationId;
    private String action;
    private String eventId;
    private Integer vid;
    private String eventType;
    private String operator;

    private String operationStatus;
    private LocalDateTime completedAt;

    private boolean replayApplied;
    private boolean duplicate;

    /** 审计中记录的历史操作 before/after（来源：repair_log.last_error 元数据）。 */
    private Integer retryCountBefore;
    private Integer retryCountAfter;
    private Integer leaseRecoveryCountBefore;
    private Integer leaseRecoveryCountAfter;
    private Integer confirmWritebackCountBefore;
    private Integer confirmWritebackCountAfter;
    private String statusNameBefore;
    private String statusNameAfter;

    /** 查询时的实时 outbox 状态；与上面的历史 before/after 分开，避免覆盖原记录。 */
    private String currentStatusName;
    private Integer currentRetryCount;
    private Integer currentLeaseRecoveryCount;
    private Integer currentConfirmWritebackCount;

    private String message;
}
