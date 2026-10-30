package com.feibijiubi.backend.videostats.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * T10-B1 consumed status3 受控重放的结果视图。
 *
 * <ul>
 *   <li>{@code replayApplied=true}：本次请求在同一 MySQL 事务里把消费账本
 *       {@code REPAIR_REQUIRED(3)} 重置为 {@code RECEIVED(0)}、把同一 eventId 的 Outbox
 *       从 {@code SENT(2)} 退回 {@code PENDING(0)}，并写入 {@code COMPLETED} 审计；</li>
 *   <li>{@code duplicate=true}：同一 operationId + 相同目标/动作/操作人/原因之前已完成，
 *       本次没有执行任何动作；before/after 从审计元数据原样重放；</li>
 *   <li>{@code deliveryMode=OUTBOX_PENDING_RELAY}：消息<b>不是</b>由请求线程发送的，
 *       而是由既有 Outbox relay 领取、confirm、失败退避与租约恢复投递；</li>
 *   <li>{@code current*} 是查询时的实时状态，不会覆盖历史 before/after。</li>
 * </ul>
 *
 * <p>不包含 payload、lease token 或 {@code last_error} 原文。</p>
 */
@Data
public class VideoStatusStatus3ReplayVO {

    private String operationId;
    private String action;
    private String source;
    private String eventId;
    private Integer vid;
    private String eventType;
    private String operator;

    private String operationStatus;
    private LocalDateTime completedAt;
    private boolean replayApplied;
    private boolean duplicate;

    /** 审计中记录的历史操作 before/after（来源：repair_log.last_error 元数据）。 */
    private Integer processStatusBefore;
    private Integer processStatusAfter;
    private Integer consumerRetryCountBefore;
    private Integer consumerRetryCountAfter;
    private String outboxStatusNameBefore;
    private String outboxStatusNameAfter;
    private Long consumedAgeSeconds;
    private Integer replayAttemptNo;
    private Long processedTtlSecondsAtReplay;

    private String deliveryMode;

    /** 查询时的实时状态；与上面的历史 before/after 分开，避免覆盖原记录。 */
    private String currentProcessStatusName;
    private Integer currentConsumerRetryCount;
    private String currentOutboxStatusName;

    private String message;
}
