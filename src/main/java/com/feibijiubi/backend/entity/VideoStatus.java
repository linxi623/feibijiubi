package com.feibijiubi.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoStatus {
    private Integer vid;
    private Integer playTimes;
    private Integer likeTimes;
    private Integer unlikeTimes;
    private Integer commentTimes;
    private Integer coinTimes;
    private Integer shareTimes;
    private Integer collectTimes;
    private Integer danmuTimes;
}
