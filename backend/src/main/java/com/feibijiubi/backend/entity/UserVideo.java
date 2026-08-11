package com.feibijiubi.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserVideo {
    private Integer id;
    private Integer vid;
    private Integer uid;
    private Double playTime;
    private Boolean liked;
    private Boolean unliked;
    private Byte coin;
    private Boolean collect;
    private LocalDateTime playedAt;
    private LocalDateTime likedAt;
    private LocalDateTime coinedAt;
}
