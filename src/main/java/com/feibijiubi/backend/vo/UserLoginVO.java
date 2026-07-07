package com.feibijiubi.backend.vo;

import lombok.Data;

@Data
public class UserLoginVO {
    private String token;
    private UserVO user;
}
