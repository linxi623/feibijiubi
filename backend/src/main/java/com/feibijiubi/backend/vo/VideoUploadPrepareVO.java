package com.feibijiubi.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoUploadPrepareVO {
    private String tempKey;
    private String bucket;
    private String region;
    private String tmpSecretId;
    private String tmpSecretKey;
    private String sessionToken;
    private Long startTime;
    private Long expiredTime;
    private Long maxFileSize;
}
