package com.feibijiubi.backend.entity;


import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UploadTempFile {
    private Integer id;
    private Integer uid;
    private Byte fileType;
    private String objectKey;
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private Byte status;
    private LocalDateTime expireAt;
    private LocalDateTime createAt;
    private LocalDateTime updateAt;
}
