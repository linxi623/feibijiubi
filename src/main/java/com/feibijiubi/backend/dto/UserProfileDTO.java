package com.feibijiubi.backend.dto;

import lombok.Data;

@Data
public class UserProfileDTO {
    private String nickname;
    private Byte gender;
    private String description;
}
