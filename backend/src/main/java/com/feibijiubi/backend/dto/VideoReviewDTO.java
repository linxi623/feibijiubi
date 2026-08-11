package com.feibijiubi.backend.dto;


import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VideoReviewDTO {

    @NotBlank(message = "审核结果不能为空")
    private String result;

    private String reason;
}
