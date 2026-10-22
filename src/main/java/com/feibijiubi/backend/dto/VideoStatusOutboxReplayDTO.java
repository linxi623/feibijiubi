package com.feibijiubi.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * T07 临时运维（T10 极窄子集）：Outbox FAILED 事件受审计重放的请求体。
 *
 * <p>只允许客户端提供 <b>幂等键 + 原因</b>：</p>
 * <ul>
 *   <li>{@code operationId}：运维工单/幂等键，重复提交同一 operationId 不会重复执行；
 *       唯一键在 {@code video_status_consumption_repair_log.operation_id}；</li>
 *   <li>{@code reason}：必填，写入审计日志，禁止空原因改状态；</li>
 *   <li><b>没有</b> operator 字段：操作人只能来自登录态
 *       （{@code LoginInterceptor} 写入的 {@code currentUserId}），不能由请求体伪造。</li>
 * </ul>
 */
@Data
public class VideoStatusOutboxReplayDTO {

    @NotBlank(message = "operationId 不能为空")
    @Size(max = 64, message = "operationId 长度不能超过 64")
    private String operationId;

    @NotBlank(message = "reason 不能为空")
    @Size(max = 1000, message = "reason 长度不能超过 1000")
    private String reason;
}
