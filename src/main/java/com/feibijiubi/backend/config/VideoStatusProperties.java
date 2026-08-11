package com.feibijiubi.backend.config;


import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.video-status")
public class VideoStatusProperties {
    // 总开关，是否启用异步统计
    private boolean asyncEnabled;
    private boolean schedulingEnabled;
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
    // 7天
    private int consumerRecoveryAutoReplayMaxAgeSeconds = 604_800;
    private int cleanupMaxAttempts = 10;
    private long flushFixedDelayMs = 500;
    private int flushDirtyBatchSize = 100;
    private int flushEventBatchSize = 1000;
    private long flushRecoveryFixedDelayMs = 5000;
    private long cleanupFixedDelayMs = 1000;
    private int cleanupBatchSize = 100;


    @PostConstruct
    public void validate() {
        long redisEventTtlSeconds = Math.multiplyExact(
                (long) redisEventTtlDays,
                86_400L
        );
        if (consumerRecoveryAutoReplayMaxAgeSeconds <= 0
                || consumerRecoveryAutoReplayMaxAgeSeconds
                >= redisEventTtlSeconds) {
            throw new IllegalStateException(
                    "consumerRecoveryAutoReplayMaxAgeSeconds 必须大于 0，"
                            + "且小于 Redis 事件幂等 Key 的 TTL"
            );
        }
    }
}
