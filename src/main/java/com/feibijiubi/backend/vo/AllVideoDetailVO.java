package com.feibijiubi.backend.vo;

import com.feibijiubi.backend.entity.Video;
import com.feibijiubi.backend.entity.VideoStatus;
import lombok.Data;

@Data
public class AllVideoDetailVO {
    private Video video;
    private VideoStatus videoStatus;
    private String avatarUrl;
    private String nickname;
    private Integer videoCount;
    private Integer fansCount;
}
