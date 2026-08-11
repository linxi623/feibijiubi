package com.feibijiubi.backend.enums;

import com.feibijiubi.backend.common.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserRole {
    COMMON(0, "普通用户"),
    ADMIN(1, "管理员"),
    SUPERADMIN(2, "超级管理员");

    private final int code;
    private final String desc;

    /**
     * 根绝数据库中的状态码获取对应枚举
     */
    public static UserRole fromCode(Byte code) {
        if(code == null) {
            throw new BusinessException(500, "用户角色不能为空");
        }

        for(UserRole role : values()) {
            if(role.code == code) {
                return role;
            }
        }
        throw new BusinessException(500, "未知的角色" + code);
    }
}
