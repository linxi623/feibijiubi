package com.feibijiubi.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@ConfigurationProperties(prefix = "tencent.cos")
public class TencentCosProperties {
    private String secretId;
    private String secretKey;
    private String region;
    private String bucket;
    private String baseUrl;
}
