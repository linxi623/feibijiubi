package com.feibijiubi.backend.enums;

import com.feibijiubi.backend.common.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserGender {
    FEMALE(0, "女"),
    MALE(1, "男"),
    UNKNOWN(2, "未知");

    private final int code;
    private final String desc;

    /**
     * 根绝数据库中的状态码获取对应枚举
     */
    public static UserGender fromCode(Byte code) {
        if(code == null) {
            throw new BusinessException(500, "性别不能为空");
        }

        for(UserGender gender : values()) {
            if(gender.code == code) {
                return gender;
            }
        }
        throw new BusinessException(500, "未知的性别" + code);
    }
}
