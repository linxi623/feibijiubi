package com.feibijiubi.backend.enums;

import com.feibijiubi.backend.common.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum VideoStatusConsumeProcessStatus {
    RECEIVED(0, "已接收"),
    REDIS_APPLIED_PENDING_FLUSH(1, "Redis已应用，等待刷新"),
    FLUSHED(2, "已刷新"),
    REPAIR_REQUIRED(3, "需要修复");

    private final int code;
    private final String desc;

    /**
     * 根据数据库中的状态码获取对应枚举
     */
    public static VideoStatusConsumeProcessStatus fromCode(Integer code) {
        if (code == null) {
            throw new BusinessException(500, "视频状态消费处理状态码不能为空");
        }

        for (VideoStatusConsumeProcessStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new BusinessException(500, "未知的视频状态消费处理状态码：" + code);
    }
}