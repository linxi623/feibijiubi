package com.feibijiubi.backend.dto;

import lombok.Data;

@Data
public class VideoCreateDTO {
    private String title;
    private Integer sourceType;
    private Integer visibility;
    private Double duration;
    private String mcId;
    private String scId;
    private String tags;
    private String description;
    private String coverUrl;
    private String videoUrl;
}
