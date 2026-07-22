package com.feibijiubi.backend.enums;

import com.feibijiubi.backend.common.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum VideoStatusFlushCleanupStatus {
    PENDING(0, "待处理"),
    CLEANED(1, "已清理"),
    SKIPPED_GENERATION_CHANGED(2, "生成已变更，跳过"),
    REPAIR_REQUIRED(3, "需要修复");

    private final int code;
    private final String desc;

    /**
     * 根据数据库中的状态码获取对应枚举
     */
    public static VideoStatusFlushCleanupStatus fromCode(Integer code) {
        if (code == null) {
            throw new BusinessException(500, "视频状态清理状态码不能为空");
        }

        for (VideoStatusFlushCleanupStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new BusinessException(500, "未知的视频状态清理状态码：" + code);
    }
}