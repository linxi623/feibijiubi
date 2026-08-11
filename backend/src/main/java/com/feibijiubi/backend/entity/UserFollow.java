package com.feibijiubi.backend.entity;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserFollow {
    private Integer id;
    private Integer followerId;
    private Integer followedId;
    private LocalDateTime createdAt;
}
