package com.feibijiubi.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserVO {
    private UserPublicProfileVO userPublicProfile;
    private String username;
    private Integer coin;
    private LocalDateTime createdAt;
}
