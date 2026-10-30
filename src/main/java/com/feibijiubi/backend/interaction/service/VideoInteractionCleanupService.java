package com.feibijiubi.backend.interaction.service;

/**
 * T15 有界清理：{@code video_interaction_request}（终态请求行）与 {@code video_interaction_window}
 * （空闲窗口行）的固定 30 天保留期清理。
 *
 * <h2>保留期是产品语义，不是配置项</h2>
 * <p>两个方向都写死成常量：{@link #REQUEST_RETENTION_DAYS}（自 {@code completed_at} 起算）与
 * {@link #WINDOW_RETENTION_DAYS}（自 {@code last_counted_at} 起算，即“连续 30 天没有计数”）。
 * 理由与 {@code VideoInteractionServiceImpl#WINDOW_MINUTES} 完全一致：把保留期做成配置项，
 * 就有人能配成 0 天，窗口行每轮被删、30 分钟限计被静默绕过——这是一个结构性 bug 类别，
 * 而不是运维自由度。</p>
 *
 * <h2>保留期改变了“requestKey 永远只计一次”这句话</h2>
 * <p>当前语义：<b>记录还在</b>且在同一次清理之前 → 同一 {@code requestKey} 重放历史结果；
 * 记录被清理之后 → 同一 {@code requestKey} 按<b>新请求</b>处理，仍受 30 分钟滚动窗口约束。
 * 清理是<b>默认关闭、按批推进</b>的维护任务，<b>不是</b> 30 天到点必然发生的 TTL：一条 31 天前的记录
 * 在下一轮清理读到它之前仍然会重放。所以“过期即删除”“到点一定不能被重复提交”都不是本实现的承诺。</p>
 *
 * <h2>边界（明确不做）</h2>
 * <ul>
 *   <li>只删两张交互表自己的行：不 JOIN、不读、不改 {@code video_status_outbox} /
 *       {@code video_status_consumed_event} / {@code video_status}，不碰 Redis（含 {@code processed} 证据）；</li>
 *   <li>只删终态请求行（{@code state=1/2} 且有 {@code completed_at}）与“非 NULL 且连续 30 天未计数”的
 *       窗口行；已提交的 PENDING 行、{@code completed_at IS NULL} 的行、{@code last_counted_at IS NULL}
 *       的行<b>永不</b>进入候选（放宽它们会改变幂等/限计语义，属于另一个 bug，应由告警处理）；</li>
 *   <li>没有 HTTP 入口、没有分布式锁、没有持久化游标/水位：DELETE 天然幂等，崩溃或多实例并发最多让
 *       本轮少删几行，下一轮继续；</li>
 *   <li>不承诺硬 3 秒截止（{@code timeout=3} 只是语句超时意图）；也<b>不保证</b>优化器的访问路径与锁足迹
 *       （间隙锁/next-key 锁、锁等待时长），那必须用真实 MySQL 的 {@code EXPLAIN} 与锁观测实测，本轮未运行。</li>
 * </ul>
 *
 * <h2>调用契约</h2>
 * <p>必须在<b>没有外层事务</b>的上下文里调用：每个 mapper 操作各自独立提交，才能保证一条 DELETE 的
 * 锁持有时间只有单条语句那么长。违反该契约会直接抛 {@link IllegalStateException}，不会静默继续。</p>
 */
public interface VideoInteractionCleanupService {

    /** 终态请求行的保留期（天，自 {@code completed_at} 起算）。固定值，不可配置。 */
    int REQUEST_RETENTION_DAYS = 30;

    /** 窗口行的保留期（天，自 {@code last_counted_at} 起算，即连续未计数的天数）。固定值，不可配置。 */
    int WINDOW_RETENTION_DAYS = 30;

    /**
     * 执行一轮清理：{@code state=1} 一批、{@code state=2} 一批、空闲窗口一批，每批最多
     * {@code app.video-interaction.cleanup-batch-size} 行。
     *
     * <p>开关关闭时直接返回 {@link RoundResult#skipped()}，<b>不执行任何 mapper 调用</b>。
     * mapper 异常原样抛出（本轮中止），没有内存状态需要修复，下一轮重做即可。</p>
     */
    RoundResult cleanupOnce();

    /**
     * 单轮结果，用于日志/监控采样（T16 基线）。
     *
     * @param countedRequestsDeleted    本轮删除的 {@code state=1 COUNTED} 请求行数
     * @param suppressedRequestsDeleted 本轮删除的 {@code state=2 SUPPRESSED} 请求行数
     * @param windowsDeleted            本轮删除的空闲窗口行数
     * @param requestBatchFull          某个请求批次取满了 {@code cleanup-batch-size}（可能还有剩余）
     * @param windowBatchFull           窗口批次取满了 {@code cleanup-batch-size}
     */
    record RoundResult(
            int countedRequestsDeleted,
            int suppressedRequestsDeleted,
            int windowsDeleted,
            boolean requestBatchFull,
            boolean windowBatchFull
    ) {

        /** 本轮删除的总行数（不含“被守卫拦下而未删”的候选）。 */
        public int totalDeleted() {
            return countedRequestsDeleted + suppressedRequestsDeleted + windowsDeleted;
        }

        /** 开关关闭时的空结果：零删除、零 mapper 调用。 */
        public static RoundResult skipped() {
            return new RoundResult(0, 0, 0, false, false);
        }
    }
}
