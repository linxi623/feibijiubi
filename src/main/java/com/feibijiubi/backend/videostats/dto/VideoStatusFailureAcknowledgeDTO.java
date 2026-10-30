package com.feibijiubi.backend.videostats.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * T10 第一阶段：管理员“确认已阅失败事件”的请求体。
 * 该动作只写审计，不修改事件/计数/Redis；操作人只来自 currentUserId。
 */
@Data
public class VideoStatusFailureAcknowledgeDTO {
    @NotBlank(message = "operationId 不能为空")
    @Size(max = 64, message = "operationId 长度不能超过 64")
    private String operationId;

    @NotBlank(message = "reason 不能为空")
    @Size(max = 1000, message = "reason 长度不能超过 1000")
    private String reason;
}
