package com.feibijiubi.backend.dto;

import lombok.Data;

@Data
public class UserRegisterDTO {
    private String username;
    private String password;
    private String confirmedPassword;
}
