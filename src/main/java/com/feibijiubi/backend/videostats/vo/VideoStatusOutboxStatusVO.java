package com.feibijiubi.backend.videostats.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员查询单个 Outbox 事件的安全视图。
 *
 * <p>刻意<b>不包含</b>：</p>
 * <ul>
 *   <li>{@code payload}：可能包含业务明细，定位问题只需要“是否可安全重放”，不需要原文；</li>
 *   <li>{@code lease_token}：投递所有权凭证，泄露后可能被用来干扰 relay 的回写 CAS；</li>
 *   <li>{@code last_error} <b>原文</b>：历史版本曾经把异常/请求细节（可能含 payload 片段或凭证）
 *       截断后写入该列，直接回显给 HTTP 等于泄露。这里只返回
 *       {@link #lastErrorPresent} 与固定提示 {@link #lastErrorHint}，
 *       需要原文时由管理员受控查库诊断。</li>
 * </ul>
 */
@Data
public class VideoStatusOutboxStatusVO {

    private String eventId;
    private Integer vid;
    private String eventType;
    private Integer status;
    private String statusName;
    private Integer retryCount;
    private Integer leaseRecoveryCount;
    private Integer confirmWritebackCount;
    private LocalDateTime createdAt;
    private LocalDateTime nextRetryAt;
    private LocalDateTime sendingAt;
    private LocalDateTime sentAt;

    /** 是否存在失败详情（只给布尔，不给原文）。 */
    private Boolean lastErrorPresent;

    /** {@code lastErrorPresent=true} 时的固定安全提示；否则为 null。 */
    private String lastErrorHint;

    /** 当前状态是否可能通过 {@code POST .../replay} 安全重放（只是必要条件，最终以 POST 校验为准）。 */
    private boolean replayEligible;

    /** 不可重放时的明确原因；可重放时为 null。 */
    private String replayBlockedReason;

    /** 对应的消费账本状态（0/1/2/3），没有消费记录时为空；只要非空本工具就拒绝重放。 */
    private Integer consumedProcessStatus;

    private String note;
}
