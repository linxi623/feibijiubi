package com.feibijiubi.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * T10-B1：consumed status3 受控重放的请求体。
 *
 * <p>只允许客户端提供<b>幂等键 + 原因</b>：</p>
 * <ul>
 *   <li>{@code operationId}：运维工单/幂等键，唯一键在
 *       {@code video_status_consumption_repair_log.operation_id}，重复提交不会重复执行；</li>
 *   <li>{@code reason}：必填，写入审计日志，禁止空原因改状态；</li>
 *   <li><b>没有</b> operator 字段：操作人只能来自登录态 {@code currentUserId}，
 *       不能由请求体伪造；</li>
 *   <li><b>没有</b> eventId/vid/payload 字段：目标只能取 URL 上的 eventId，事件内容只能取
 *       数据库中的原 payload，客户端无法构造待发送事件。</li>
 * </ul>
 */
@Data
public class VideoStatusStatus3ReplayDTO {

    @NotBlank(message = "operationId 不能为空")
    @Size(max = 64, message = "operationId 长度不能超过 64")
    private String operationId;

    @NotBlank(message = "reason 不能为空")
    @Size(max = 1000, message = "reason 长度不能超过 1000")
    private String reason;
}
