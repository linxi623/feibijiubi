package com.feibijiubi.backend.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.video-status")
public class VideoStatusProperties {
    // 总开关，是否启用异步统计
    private boolean asyncEnabled;
    private int redisEventTtlDays = 30;
    // 每次从数据库中抓取多少条待发布消息
    private int outboxBatchSize = 100;
    // 扫描间隔，决定了抓取数据库的频率
    private long outboxFixedDelayMs = 1000;
    // 分布式租约过期时间，某个实例处理不完由其他实例接管，防止死锁
    private int outboxLeaseSeconds = 60;
    // 消费者返回确认消息的最长时间，超时定义为发送失败
    private int publishConfirmTimeoutSeconds = 5;
    private int consumerMaxRetries = 5;
}
