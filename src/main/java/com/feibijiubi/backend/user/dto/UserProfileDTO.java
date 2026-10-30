package com.feibijiubi.backend.user.dto;

import lombok.Data;

@Data
public class UserProfileDTO {
    private String nickname;
    private Byte gender;
    private String description;
}
