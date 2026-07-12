package com.feibijiubi.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserVO {
    private Integer id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private String backgroundUrl;
    private Integer gender;
    private String description;
    private Integer experience;
    private Integer coin;
    private Byte vip;
    private Byte status;
    private Byte role;
    private Byte auth;
    private String authMsg;
    private LocalDateTime createdAt;
    private UserCountVO userCount;
    private Boolean subscribed;
}
