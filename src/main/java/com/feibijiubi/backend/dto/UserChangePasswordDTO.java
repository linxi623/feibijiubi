package com.feibijiubi.backend.dto;

import lombok.Data;

@Data
public class UserChangePasswordDTO {
    private String oldPassword;
    private String newPassword;
    private String confirmedPassword;
}
