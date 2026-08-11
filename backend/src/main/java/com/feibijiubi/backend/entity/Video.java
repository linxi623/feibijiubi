package com.feibijiubi.backend.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Video {
    private Integer vid;
    private Integer uid;
    private String title;
    private Integer sourceType;
    private Byte visibility;
    private Double duration;
    private String mcId;   // 主分区ID
    private String scId;   // 子分区ID
    private String tags;
    private String description;
    private String coverUrl;
    private String coverKey;
    private String videoUrl;
    private String videoKey;
    private Byte status;     // 0审核中 1通过审核 2打回整改（指投稿信息不符） 3视频违规删除（视频内容违规）
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
}
