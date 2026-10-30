package com.feibijiubi.backend.videostats.entity;

import lombok.Data;

/**
 * T10 对账接口使用的安全事件投影行。
 *
 * <p>刻意只选择 {@code id / process_status / event_type / delta}：
 * 不读取 {@code payload}、{@code last_error}、租约 token 等可能含业务明细或
 * 凭证的列。该对象只用于有界窗口内的只读汇总，不参与任何写入。</p>
 */
@Data
public class VideoStatusReconciliationEventRow {
    private Long id;
    private Integer processStatus;
    private String eventType;
    private Long delta;
}
