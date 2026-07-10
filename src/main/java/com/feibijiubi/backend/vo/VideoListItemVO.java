package com.feibijiubi.backend.vo;


import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoListItemVO {
    private Integer vid;
    private Integer uid;
    private String title;
    private String coverUrl;
    private Double duration;
    private Integer playTimes;
    private Integer commentTimes;
    private LocalDateTime createdAt;
}
