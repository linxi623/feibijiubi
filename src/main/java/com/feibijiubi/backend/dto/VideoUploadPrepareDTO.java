package com.feibijiubi.backend.dto;


import lombok.Data;

@Data
public class VideoUploadPrepareDTO {
    private String fileName;
    private String contentType;
    private Long fileSize;
}
