package com.feibijiubi.backend.enums;

import com.feibijiubi.backend.common.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;


@Getter
@AllArgsConstructor
public enum UserAuth {
    COMMON(0, "普通用户"),
    SINGLE(1, "个人认证"),
    ORGANIZATION(2, "机构认证");

    private final int code;
    private final String desc;

    /**
     * 根绝数据库中的状态码获取对应枚举
     */
    public static UserAuth fromCode(Integer code) {
        if(code == null) {
            throw new BusinessException(500, "视频审核转态不能为空");
        }

        for(UserAuth auth : values()) {
            if(auth.code == code) {
                return auth;
            }
        }
        throw new BusinessException(500, "未知的视频审核转态：" + code);
    }

}
