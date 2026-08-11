package com.feibijiubi.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VideoSubmitDTO {
    @NotBlank(message = "标题不能为空")
    private String title;

    @NotNull(message = "视频来源类型不能为空")
    private Integer sourceType;

    @NotNull(message = "视频可见性不能为空")
    private Integer visibility;

    @NotNull(message = "视频时长不能为空")
    private Double duration;

    @NotBlank(message = "主分区不能为空")
    private String mcId;

    @NotBlank(message = "子分区不能为空")
    private String scId;

    private String tags;
    private String description;

    @NotBlank(message = "封面临时文件不能为空")
    private String tempCoverKey;

    @NotBlank(message = "视频临时文件不能为空")
    private String tempVideoKey;
}
