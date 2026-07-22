package com.feibijiubi.backend.enums;

import com.feibijiubi.backend.common.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.coyote.BadRequestException;


@Getter
@AllArgsConstructor
public enum VideoReviewStatus {
    PENDING(0, "待审核"),
    APPROVED(1, "审核通过"),
    REJECTED(2, "审核驳回"),
    REMOVED(3, "违规删除");

    private final int code;
    private final String desc;


    /**
     * 根绝数据库中的状态码获取对应枚举
     */
    public static VideoReviewStatus fromCode(Byte code) {
        if(code == null) {
            throw new BusinessException(500, "视频审核转态不能为空");
        }

        for(VideoReviewStatus status : values()) {
            if(status.code == code) {
                return status;
            }
        }
        throw new BusinessException(500, "未知的视频审核转态：" + code);
    }

    /**
     * 判断当前状态是否允许流转到目标状态
     */
    public boolean canTransitionTo(VideoReviewStatus targetStatus) {
        if(targetStatus == null) {
            return false;
        }
        if(this == PENDING) {
            return targetStatus == APPROVED || targetStatus == REJECTED;
        } else {
            return targetStatus == REMOVED || targetStatus == APPROVED;
        }

    }
}
