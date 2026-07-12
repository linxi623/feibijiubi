package com.feibijiubi.backend.enums;

import com.feibijiubi.backend.common.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;


@Getter
@AllArgsConstructor
public enum UserVip {
    COMMON(0, "普通用户"),
    MONTH_VIP(1, "月度大会员"),
    SEASON_VIP(2, "季度大会员"),
    YEAR_VIP(3, "年度大会员");

    private final int code;
    private final String desc;

    /**
     * 根绝数据库中的状态码获取对应枚举
     */
    public static UserVip fromCode(Byte code) {
        if(code == null) {
            throw new BusinessException(500, "用户会员情况不能为空");
        }

        for(UserVip vip : values()) {
            if(vip.code == code) {
                return vip;
            }
        }
        throw new BusinessException(500, "未知的用户状态：" + code);
    }
}
