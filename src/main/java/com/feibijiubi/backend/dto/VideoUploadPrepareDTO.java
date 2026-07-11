package com.feibijiubi.backend.dto;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class VideoUploadPrepareDTO {
    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @NotBlank(message = "文件类型不能为空")
    private String contentType;

    @NotNull(message = "文件大小不能为空")
    private Long fileSize;
}
