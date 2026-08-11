package com.feibijiubi.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminVideoListItemVO {
    private Integer vid;
    private Integer uid;
    private String title;
    private String coverUrl;
    private Double duration;
    private LocalDateTime createdAt;
}
