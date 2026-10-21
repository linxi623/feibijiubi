package com.feibijiubi.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminVideoDetailVO {
    private Integer vid;
    private Integer uid;
    private String title;
    private Integer sourceType;
    private Byte visibility;
    private Double duration;
    private String mcId;
    private String scId;
    private String tags;
    private String description;
    private String coverUrl;
    private String videoUrl;
    private Byte status;
    private LocalDateTime createdAt;

    private Long playTimes;
    private Long likeTimes;
    private Long unlikeTimes;
    private Long commentTimes;
    private Long coinTimes;
    private Long shareTimes;
    private Long collectTimes;
    private Long danmuTimes;

    private String avatarUrl;
    private String nickname;
    private Integer videoCount;
    private Integer fansCount;
}
