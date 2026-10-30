package com.feibijiubi.backend.videostats.config;


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
     * T12-B：重建候选 keyset 分页的固定页大小上限。
     *
     * <p>一页最多 1000 行是“单次数据库往返 + 单次转换循环”的显式上界，不是性能调优旋钮：
     * 页大小越大，单页内存与一次转换的耗时越长，预算检查的粒度也越粗。</p>
     */
    public static final int MAX_REBUILD_CANDIDATE_PAGE_SIZE = 1_000;
    /**
     * T12-B：重建候选总数上限允许配置到的最大值。
     *
     * <p>候选 list 是“有界”而不是“常数内存”：上界就是本项配置。给一个远高于默认值的
     * 硬上限，是为了防止把 {@code rebuild-max-candidates} 误配成近似无界的数字后，
     * 又回到“一个视频的候选全量进堆”的老问题。</p>
     */
    public static final int MAX_REBUILD_MAX_CANDIDATES = 10_000_000;
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
    /** 与 VideoStatusMessageForwarder 的分级重试队列 TTL 保持一致（秒）。 */
    public static final long RETRY_BACKOFF_FIRST_SECONDS = 10L;
    public static final long RETRY_BACKOFF_SECOND_SECONDS = 30L;
    public static final long RETRY_BACKOFF_LATER_SECONDS = 120L;

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
    // T08：重试/DLQ 转发失败后，先异步停止消费者容器，再等待该时长后恢复。
    // 不能在 listener 线程同步 stop（会等待自己退出而超时/死锁），也不能立即 requeue 热循环。
    private long consumerRecoveryDelayMs = 10_000;
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

    // ------------------------------------------------------------------ T09
    // 默认关闭：status0 自动补偿会重新投递原事件，必须先由运维明确确认
    // “processed 幂等证据与 Redis 数据丢失边界”，否则只允许只读 preview。
    private boolean status0CompensationEnabled;
    // 事故闸门/完整性确认。enabled=true 而本项=false 时启动失败，
    // 避免一次配置误开就自动累计不确定的事件。
    private boolean status0CompensationIntegrityConfirmed;
    // 受控恢复模式，默认 false。只有操作员已经明确确认“本次事故没有 Redis 数据丢失、
    // 没有 current/processed 部分写事故”时才可开启；开启后允许在安全窗口内对
    // processed 缺失的原 eventId 重放。它不能自动检测“单独丢 processed”，也不把
    // 缺失推断成“未应用”；事故处置时必须先关闭 status0-compensation-enabled 总开关。
    private boolean status0CompensationAllowUnprovenReplay;
    // 新鲜事件不抢救：RECEIVED 至少静默这么久才进入候选。
    private long status0CompensationMinAgeSeconds = 60;
    private int status0CompensationBatchSize = 20;
    private long status0CompensationFixedDelayMs = 5_000;
    // 领取后给 MQ confirm + 回写留出的租约；到期可被其它实例再次领取。
    private int status0CompensationLeaseSeconds = 30;
    // 失败/证据不足后的冷却，避免每轮重复扫描同一条事件。
    private long status0CompensationCooldownSeconds = 120;
    // 有界单轮/有界生命周期：达到上限后只诊断，不自动转 status3。
    private int status0CompensationMaxAttempts = 5;
    // preview/日志的超龄事件计数上限：只返回 capped count，等于上限时标记为 lower bound，
    // 避免每次 preview 对全表做无界 COUNT。
    private int status0CompensationOverSafeWindowCountCap = 1000;

    // ------------------------------------------------------------------ T10-B1
    // consumed status3 受控重放的尝试上限：按 repair_log 中同一 eventId、
    // action=REPLAY_CONSUMED_TO_RECEIVED 的已完成审计行计数（有界查询）。
    // 重放后若刷库再次数据异常，行会重新落回 status3；没有上限就会形成
    // “重放→再入 status3→再重放”的人工死循环，因此这里只允许有限次人工干预。
    private int status3ReplayMaxAttempts = 3;

    // ------------------------------------------------------------------ T12-B
    // 重建候选的 keyset 分页页大小（一页最多 1000 行，不允许 OFFSET）。
    private int rebuildCandidatePageSize = MAX_REBUILD_CANDIDATE_PAGE_SIZE;
    // 单个视频允许的重建候选总数上限：超过即失败重试，绝不返回截断快照。
    // 默认 100000 覆盖当前数据规模，同时把最坏情况的内存/转换成本写进配置而不是隐含在代码里。
    private int rebuildMaxCandidates = 100_000;
    // 快照读取预算（毫秒）：从 load() 开始读基线起计时，页间与逐条转换时检查。
    // 它只决定“还要不要继续读下一页/转下一条”，不会中断已在执行的 SQL。
    private long rebuildSnapshotBudgetMs = 10_000L;


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
        requirePositiveDelay("consumerRecoveryDelayMs", consumerRecoveryDelayMs);
        requirePositiveDelay("roundBudgetMs", roundBudgetMs);

        requireRange("status0CompensationBatchSize", status0CompensationBatchSize,
                1, MAX_BATCH_SIZE);
        requireRange("status0CompensationLeaseSeconds", status0CompensationLeaseSeconds,
                1, MAX_LEASE_OR_CONFIRM_TIMEOUT_SECONDS);
        requireRange("status0CompensationMaxAttempts", status0CompensationMaxAttempts,
                1, MAX_CONSUMER_MAX_RETRIES);
        requireRange("status0CompensationOverSafeWindowCountCap",
                status0CompensationOverSafeWindowCountCap, 1, MAX_BATCH_SIZE);
        requireRange("status3ReplayMaxAttempts", status3ReplayMaxAttempts,
                1, MAX_CONSUMER_MAX_RETRIES);
        requireRange("rebuildCandidatePageSize", rebuildCandidatePageSize,
                1, MAX_REBUILD_CANDIDATE_PAGE_SIZE);
        requireRange("rebuildMaxCandidates", rebuildMaxCandidates,
                1, MAX_REBUILD_MAX_CANDIDATES);
        requirePositiveDelay("rebuildSnapshotBudgetMs", rebuildSnapshotBudgetMs);
        requirePositiveDelay("status0CompensationMinAgeSeconds",
                status0CompensationMinAgeSeconds);
        requirePositiveDelay("status0CompensationFixedDelayMs",
                status0CompensationFixedDelayMs);
        requirePositiveDelay("status0CompensationCooldownSeconds",
                status0CompensationCooldownSeconds);
        // 补偿租约必须覆盖一次 publish confirm 并留回写余量；否则整批领取后
        // 后面的条目可能在处理前就被其它实例合法接管。
        int minimumStatus0LeaseSeconds = publishConfirmTimeoutSeconds
                + MIN_LEASE_CONFIRM_MARGIN_SECONDS;
        if (status0CompensationLeaseSeconds < minimumStatus0LeaseSeconds) {
            throw new IllegalStateException(
                    "app.video-status.status0-compensation-lease-seconds 必须 >= "
                            + "publish-confirm-timeout-seconds + "
                            + MIN_LEASE_CONFIRM_MARGIN_SECONDS + " 秒（当前 confirm 超时 = "
                            + publishConfirmTimeoutSeconds + " 秒，要求租约 >= "
                            + minimumStatus0LeaseSeconds + " 秒，实际租约 = "
                            + status0CompensationLeaseSeconds + " 秒）"
            );
        }
        // 安全窗口仍复用 consumerRecoveryAutoReplayMaxAgeSeconds：它已强制小于
        // Redis processed TTL。补偿只在这个窗口内工作，且必须晚于最小年龄。
        if (status0CompensationMinAgeSeconds
                >= consumerRecoveryAutoReplayMaxAgeSeconds) {
            throw new IllegalStateException(
                    "app.video-status.status0-compensation-min-age-seconds 必须小于"
                            + " consumer-recovery-auto-replay-max-age-seconds（安全窗口 = "
                            + consumerRecoveryAutoReplayMaxAgeSeconds + " 秒，当前最小年龄 = "
                            + status0CompensationMinAgeSeconds + " 秒）"
            );
        }
        if (status0CompensationEnabled
                && !status0CompensationIntegrityConfirmed) {
            throw new IllegalStateException(
                    "app.video-status.status0-compensation-enabled=true 需要同时设置"
                            + " status0-compensation-integrity-confirmed=true"
                            + "（显式确认 processed 证据与 Redis 丢失边界；否则只允许只读 preview）"
            );
        }
        // allow-unproven-replay 不强制依赖 enabled：事故中常用“关闭总开关、保留
        // 受控恢复配置”的方式停机，不能因此让应用无法启动。三条件只在 runOnce 内合成。

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

    /**
     * 重放消息从进入 MQ 到最后一次消费失败所允许的最长重试退避，再加上
     * confirm/补偿租约余量。processed Key 的剩余 TTL 小于该值时，重试期间
     * 幂等证据可能先过期，必须拒绝发送而不是仅检查 Key 当前是否存在。
     */
    public long getStatus0CompensationRequiredProcessedTtlSeconds() {
        long leaseOrConfirmSeconds = Math.max(
                status0CompensationLeaseSeconds,
                publishConfirmTimeoutSeconds
        );
        return Math.addExact(mqRetryBackoffSeconds(), leaseOrConfirmSeconds);
    }

    /**
     * T10-B1：consumed status3 受控重放（proven 路径）要求 processed Key 的最小剩余 TTL。
     *
     * <p>重放不再由本线程直接 publish，而是把 Outbox 行从 SENT 退回 PENDING，交给既有
     * relay 领取/confirm/租约恢复投递。因此“从此刻到消费者真正应用这条消息”的最大耗时
     * 需要覆盖 Outbox 投递租约（租约过期后由恢复流程接管继续投递），而不只是 T09 的
     * 补偿租约；这里取所有投递/回写余量中的最大值，比 T09 更保守。</p>
     */
    public long getStatus3ReplayRequiredProcessedTtlSeconds() {
        long deliverySeconds = Math.max(
                Math.max(outboxLeaseSeconds, status0CompensationLeaseSeconds),
                publishConfirmTimeoutSeconds
        );
        return Math.addExact(mqRetryBackoffSeconds(), deliverySeconds);
    }

    /**
     * 重放消息从进入 MQ 到最后一次消费失败所允许的最长重试退避（秒）。
     * T09 的 status0 补偿与 T10-B1 的 status3 重放共用同一份计算，避免出现两种口径。
     */
    private long mqRetryBackoffSeconds() {
        long retryBackoffSeconds = 0L;
        for (int attempt = 1; attempt <= consumerMaxRetries; attempt++) {
            if (attempt <= 1) {
                retryBackoffSeconds = Math.addExact(
                        retryBackoffSeconds, RETRY_BACKOFF_FIRST_SECONDS);
            } else if (attempt == 2) {
                retryBackoffSeconds = Math.addExact(
                        retryBackoffSeconds, RETRY_BACKOFF_SECOND_SECONDS);
            } else {
                retryBackoffSeconds = Math.addExact(
                        retryBackoffSeconds, RETRY_BACKOFF_LATER_SECONDS);
            }
        }
        return retryBackoffSeconds;
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
