package com.feibijiubi.backend.dto;

import lombok.Data;

@Data
public class UserChangePasswordDTO {
    private String oldPassword;
    private String newPasswoed;
    private String confirmedpassword;
}
