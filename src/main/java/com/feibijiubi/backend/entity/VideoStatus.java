package com.feibijiubi.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoStatus {
    private Integer vid;
    // T02: 计数统一 Long，支持 0..Long.MAX_VALUE；vid 等标识仍为 Integer。
    private Long playTimes;
    private Long likeTimes;
    private Long unlikeTimes;
    private Long commentTimes;
    private Long coinTimes;
    private Long shareTimes;
    private Long collectTimes;
    private Long danmuTimes;
}
