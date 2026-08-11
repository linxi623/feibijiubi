package com.feibijiubi.backend.vo;

import lombok.Data;

@Data
public class UserCountVO {
    private Integer fansCount;
    private Integer starCount;
    private Integer loveCount;
    private Integer videoCount;
}
