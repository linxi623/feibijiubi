package com.feibijiubi.backend.config;


import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 视频统计链路的开关与调优参数。
 *
 * <p>四个运行角色（详见 docs/tutorial/video-status-runtime-roles.md）：</p>
 * <ul>
 *   <li>业务发布：{@code async-enabled}，业务事务内写 Outbox 而不是直接改 Redis/DB；</li>
 *   <li>投递：{@code outbox-relay-enabled}，本实例是否领取 Outbox 并发布到 RabbitMQ；</li>
 *   <li>消费：由 {@code spring.rabbitmq.listener.simple.auto-startup} 控制，不在本配置组；</li>
 *   <li>落库/恢复：{@code scheduling-enabled}，本实例是否执行批量刷库与 dirty 恢复调度。</li>
 * </ul>
 *
 * <p>这里的取值校验放在 {@link #validate()}（{@code @PostConstruct}）而不是
 * {@code @Validated} + 单字段约束：启动期即可失败、无需 Spring 代理、能表达
 * “重放安全窗口必须小于 Redis 幂等 TTL”这类跨字段规则，错误信息也带实际取值。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.video-status")
public class VideoStatusProperties {
    /** 单次批量上限：拦截少写/多写一个 0 的笔误，不是性能调优上限。 */
    public static final int MAX_BATCH_SIZE = 10_000;
    /** 调度间隔上限（毫秒）：一小时已经远大于任何合理轮询间隔。 */
    public static final long MAX_SCHEDULER_DELAY_MS = 3_600_000L;
    public static final int MAX_REDIS_EVENT_TTL_DAYS = 3_650;
    public static final int MAX_CONSUMER_MAX_RETRIES = 100;
    public static final int MAX_LEASE_OR_CONFIRM_TIMEOUT_SECONDS = 3_600;
    /**
     * T07：投递租约必须比 publish confirm 超时多出的安全余量（秒）。
     *
     * <p>confirm 只是“等 MQ 回执”的上限；拿到回执之后还要做 markSent / markConfirmedPending /
     * markInvalidFailed 的状态回写。若 {@code outbox-lease-seconds} 紧贴 confirm 超时，
     * 回写就可能落在别的实例已经合法接管之后，让旧 owner 的 CAS 全部落空。
     * 固定留 5 秒给回写与时钟误差；默认 60 / 5 自然满足。</p>
     */
    public static final int MIN_LEASE_CONFIRM_MARGIN_SECONDS = 5;
    /** 单轮调度预算默认值（毫秒）。 */
    public static final long DEFAULT_ROUND_BUDGET_MS = 5_000L;

    // 总开关，是否启用异步统计（业务侧写 Outbox、消费者落 Redis、调度器落库都以此为前提）
    private boolean asyncEnabled;
    // 是否由本实例承担 Outbox→RabbitMQ 投递角色。默认 true，保持历史行为不变；
    // 关闭时本实例不领取、不恢复租约、不发布，但消费与批量落库/恢复照常工作。
    private boolean outboxRelayEnabled = true;
    private boolean schedulingEnabled;
    private int redisEventTtlDays = 30;
    // 每次从数据库中抓取多少条待发布消息
    private int outboxBatchSize = 100;
    // 扫描间隔，决定了抓取数据库的频率
    private long outboxFixedDelayMs = 1000;
    // 分布式租约过期时间，某个实例处理不完由其他实例接管，防止死锁
    private int outboxLeaseSeconds = 60;
    // mq返回确认消息的最长时间，超时定义为发送失败
    private int publishConfirmTimeoutSeconds = 5;

    private int consumerMaxRetries = 5;
    // 7天
    private int consumerRecoveryAutoReplayMaxAgeSeconds = 604_800;
    private long flushFixedDelayMs = 500;
    private int flushDirtyBatchSize = 100;
    private int flushEventBatchSize = 1000;
    private long flushRecoveryFixedDelayMs = 5000;
    // 单轮调度预算（毫秒），单位与 T04 的其它时长配置一致。
    // 投递 / 落库 / 恢复三条调度线程各自在每轮开始时记起点，在“领取或处理下一条之前”检查：
    // 预算用尽就结束本轮，未处理的工作留在 Outbox / Redis dirty / 消费事件表里等下一轮。
    // 它只限制“本轮还要不要继续拿新工作”，不能强杀正在进行的 publish confirm 或数据库事务
    // （那些由 confirm timeout、vid 锁等待和 SQL 超时各自界定）。
    private long roundBudgetMs = DEFAULT_ROUND_BUDGET_MS;


    /**
     * 启动期参数校验。任何非法取值都在启动阶段抛出可读错误，
     * 不允许带着错误配置进入运行期才发现（例如 replay 窗口大于幂等 TTL 会丢去重证据）。
     */
    @PostConstruct
    public void validate() {
        requireRange("redisEventTtlDays", redisEventTtlDays,
                1, MAX_REDIS_EVENT_TTL_DAYS);

        long redisEventTtlSeconds = Math.multiplyExact(
                (long) redisEventTtlDays,
                86_400L
        );
        if (consumerRecoveryAutoReplayMaxAgeSeconds <= 0
                || consumerRecoveryAutoReplayMaxAgeSeconds
                >= redisEventTtlSeconds) {
            throw new IllegalStateException(
                    "app.video-status.consumer-recovery-auto-replay-max-age-seconds 必须大于 0，"
                            + "且小于 Redis 事件幂等 Key 的 TTL"
                            + "（当前 TTL = " + redisEventTtlSeconds
                            + " 秒，重放安全窗口 = "
                            + consumerRecoveryAutoReplayMaxAgeSeconds + " 秒）"
            );
        }

        requireRange("outboxBatchSize", outboxBatchSize, 1, MAX_BATCH_SIZE);
        requireRange("flushDirtyBatchSize", flushDirtyBatchSize, 1, MAX_BATCH_SIZE);
        requireRange("flushEventBatchSize", flushEventBatchSize, 1, MAX_BATCH_SIZE);

        requirePositiveDelay("outboxFixedDelayMs", outboxFixedDelayMs);
        requirePositiveDelay("flushFixedDelayMs", flushFixedDelayMs);
        requirePositiveDelay("flushRecoveryFixedDelayMs", flushRecoveryFixedDelayMs);

        requireRange("outboxLeaseSeconds", outboxLeaseSeconds,
                1, MAX_LEASE_OR_CONFIRM_TIMEOUT_SECONDS);
        requireRange("publishConfirmTimeoutSeconds", publishConfirmTimeoutSeconds,
                1, MAX_LEASE_OR_CONFIRM_TIMEOUT_SECONDS);
        requireRange("consumerMaxRetries", consumerMaxRetries,
                1, MAX_CONSUMER_MAX_RETRIES);
        requirePositiveDelay("roundBudgetMs", roundBudgetMs);

        // T07 跨字段约束：租约必须 >= confirm 超时 + 5s，给状态回写留出余量。
        // 只约束这两个字段；不要求 roundBudgetMs >= confirm（单轮预算只决定“还要不要拿下一条”，
        // 不负责强杀进行中的 publish，若强行要求会把已有的 5s 预算配置判成非法）。
        int minimumLeaseSeconds = publishConfirmTimeoutSeconds
                + MIN_LEASE_CONFIRM_MARGIN_SECONDS;
        if (outboxLeaseSeconds < minimumLeaseSeconds) {
            throw new IllegalStateException(
                    "app.video-status.outbox-lease-seconds 必须 >= publish-confirm-timeout-seconds + "
                            + MIN_LEASE_CONFIRM_MARGIN_SECONDS + " 秒（当前 confirm 超时 = "
                            + publishConfirmTimeoutSeconds + " 秒，要求租约 >= "
                            + minimumLeaseSeconds + " 秒，实际租约 = "
                            + outboxLeaseSeconds + " 秒）"
            );
        }
    }

    private void requirePositiveDelay(String name, long value) {
        if (value <= 0 || value > MAX_SCHEDULER_DELAY_MS) {
            throw new IllegalStateException(
                    "app.video-status." + kebab(name) + " 必须在 1 到 "
                            + MAX_SCHEDULER_DELAY_MS + " 毫秒之间，当前值：" + value
            );
        }
    }

    private void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalStateException(
                    "app.video-status." + kebab(name) + " 必须在 " + min
                            + " 到 " + max + " 之间，当前值：" + value
            );
        }
    }

    private String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }
}
