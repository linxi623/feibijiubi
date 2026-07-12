package com.feibijiubi.backend.vo;

import lombok.Data;


@Data
public class UserPublicProfileVO {
    private Integer id;
    private String nickname;
    private String avatarUrl;
    private String backgroundUrl;
    private Integer gender;
    private String description;
    private Integer experience;
    private Byte vip;
    private Byte status;
    private Byte role;
    private Byte auth;
    private String authMsg;

    private UserCountVO userCount;

    private Boolean subscribed;
}
