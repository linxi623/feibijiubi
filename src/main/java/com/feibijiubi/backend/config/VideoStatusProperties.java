package com.feibijiubi.backend.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.video-status")
public class VideoStatusProperties {
    private boolean asyncEnabled;
    private int redisEventTtlDays = 30;
    private int outboxBatchSize = 100;
    private long outboxFixedDelayMs = 1000;
    private int outboxLeaseSeconds = 60;
    private int publishConfirmTimeoutSeconds = 5;
    private int consumerMaxRetries = 5;
}
