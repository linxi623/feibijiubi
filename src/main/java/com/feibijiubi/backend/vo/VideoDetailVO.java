package com.feibijiubi.backend.vo;


import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoDetailVO {
    private Integer vid;
    private Integer uid;
    private String title;
    private Integer sourceType;
    private Double duration;
    private String mcId;
    private String scId;
    private String tags;
    private String description;
    private String coverUrl;
    private String videoUrl;
    private LocalDateTime createdAt;

    private Integer playTimes;
    private Integer likeTimes;
    private Integer coinTimes;
    private Integer collectTimes;
    private Integer commentTimes;
    private Integer danmuTimes;
    private Integer shareTimes;

    private Boolean liked;
    private Byte coin;
    private Boolean collected;
    private Double playTime;

    private String avatarUrl;
    private String nickname;
    private Integer videoCount;
    private Integer fansCount;
    private Boolean subscribed;
}
