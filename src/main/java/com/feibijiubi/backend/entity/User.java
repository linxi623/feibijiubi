package com.feibijiubi.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private Long id;
    private String username;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private String backgroundUrl;
    private Byte gender; // 性别，0女，1男，2无性别，默认2
    private String description;
    private Integer experience;// 经验值 50/200/1500/4500/10800/28800  分别是0~6级的区间
    private Integer coin;
    private Byte vip;// 0 普通用户，1 月度大会员，2 季度大会员，3 年度大会员
    private Byte status;// 0 正常，1 封禁中，2 已注销
    private Byte role;// 0 普通用户，1 普通管理员，2 超级管理员
    private Byte auth;// 0 普通用户，1 个人认证，2 机构认证
    private String authMsg;// 认证信息，如 feibijiubi官方账号

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
