package com.feibijiubi.backend.videostats.mq;

import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.config.VideoStatusSchedulingConfig;
import com.feibijiubi.backend.videostats.entity.VideoStatusOutbox;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.videostats.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.videostats.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Outbox → RabbitMQ 投递器（T07 版本）。
 *
 * <p>与旧实现的差别只在“失败怎么分类、回写失败怎么办”，投递语义仍是
 * <b>至少一次</b>：confirm 成功但 DB 回写失败时消息会被再次投递，靠消费者用
 * eventId 幂等吸收重复。本类<b>不承诺</b>跨 RabbitMQ 与 MySQL 的 exactly-once，
 * 也没有引入任何分布式事务。</p>
 *
 * <p>失败分类（互斥，按发生顺序判定）：</p>
 * <ol>
 *   <li>payload 无法反序列化 / 反序列化为空 / {@code event.validate()} 失败 /
 *       event 与 outbox 的 eventId、vid、type 语义不一致 → 非法事件，直接
 *       {@code markInvalidFailed}（不增加发布失败计数、不再重试，进入 FAILED 等 T10 处置）；</li>
 *   <li>publish 发送失败、Broker NACK/不可路由、confirm 超时或结果不确定 →
 *       发布失败：{@code markPending} + 指数退避（retry_count+1），达到
 *       {@link #MAX_PUBLISH_RETRIES} 后 {@code markFailed} 形成终态；</li>
 *   <li>confirm 成功（已确认到达 Broker）→ {@code markSent}。
 *       markSent 抛异常时改为 {@code markConfirmedPending}（不增加 retry_count，
 *       短延迟后重投，靠 eventId 幂等）；这条路径有独立的 confirm_writeback_count 预算，
 *       第 10 次仍失败就落 FAILED，不会每秒永久重投；</li>
 *   <li>任何 CAS 更新影响 0 行：说明租约已过期、状态已被新 owner 接管，
 *       只记录诊断日志，<b>不再改状态</b>；影响 &gt;1 行属于严重异常，单独 ERROR 日志。</li>
 * </ol>
 *
 * <p>所有日志只使用 reason code 与异常类名，不输出 payload/JSON 片段或凭据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatusOutboxRelay {

    /** 发布失败（含 confirm 不确定）的重试上限；租约恢复有独立上限，不共用本计数。 */
    static final int MAX_PUBLISH_RETRIES = 10;

    /**
     * confirm 已成功、但 markSent 持续失败且 markConfirmedPending 每次都能成功时的独立上限。
     *
     * <p>这种故障下消息已被 Broker 确认，不能算发布失败、也不能算租约过期；
     * 若只用“1 秒后重投”而不计数，会变成每秒永久重投。第 10 次仍无法回写时
     * 由 {@code markConfirmWritebackExhausted} 形成 FAILED 终态，等待管理员审计处置。</p>
     */
    static final int MAX_CONFIRM_WRITEBACK_RETRIES = 10;

    /**
     * confirm 成功但 markSent 回写失败时，退回 PENDING 的最短等待秒数。
     * 这段等待只为避开“同一实例立刻重投”的忙循环，不增加 retry_count。
     */
    static final long CONFIRMED_WRITEBACK_RETRY_DELAY_SECONDS = 1L;

    /** last_error 列只存短 reason，避免把异常原文（可能含 payload）写进库。 */
    private static final int MAX_LAST_ERROR_LENGTH = 200;

    /**
     * T06：每轮逐条领取。旧行为是“一次预领 outboxBatchSize 条再慢慢 publish”，
     * 租约从第一条就开始计时，整批等待 confirm 时后面的消息会先过期。
     * 改成每次只领 1 条、领完立刻发布，租约窗口只覆盖它自己。
     */
    private static final int CLAIM_ONE = 1;

    static final String REASON_PAYLOAD_JSON_INVALID = "PAYLOAD_JSON_INVALID";
    static final String REASON_PAYLOAD_EMPTY = "PAYLOAD_EMPTY";
    static final String REASON_EVENT_INVALID = "EVENT_INVALID";
    static final String REASON_EVENT_MISMATCH_EVENT_ID = "EVENT_MISMATCH_EVENT_ID";
    static final String REASON_EVENT_MISMATCH_VID = "EVENT_MISMATCH_VID";
    static final String REASON_EVENT_MISMATCH_TYPE = "EVENT_MISMATCH_TYPE";
    static final String REASON_PUBLISH_PREFIX = "PUBLISH_";
    static final String REASON_PUBLISH_UNKNOWN = "PUBLISH_FAILED";
    static final String REASON_CONFIRM_WRITEBACK_FAILED = "CONFIRM_WRITEBACK_FAILED";
    static final String REASON_CONFIRM_WRITEBACK_BUDGET_EXHAUSTED =
            "CONFIRM_WRITEBACK_BUDGET_EXHAUSTED";
    static final String REASON_CONFIRM_WRITEBACK_PENDING_FAILED =
            "CONFIRM_WRITEBACK_PENDING_FAILED";
    static final String REASON_LEASE_RECOVERY_FAILED = "LEASE_RECOVERY_FAILED";
    static final String REASON_CLAIM_FAILED = "CLAIM_FAILED";
    static final String REASON_ISOLATED_ROW_FAILURE = "ISOLATED_ROW_FAILURE";

    private final VideoStatusOutboxClaimService claimService;
    private final VideoStatusOutboxMapper outboxMapper;
    private final VideoStatusEventPublisher publisher;
    private final VideoStatusProperties properties;
    private final JsonUtils jsonUtils;

    /**
     * T05 只读观测：允许为 null（纯构造测试/未装配指标的隔离配置）。
     * 指标失败只丢样本，不影响领取、发布与状态回写。
     */
    @Autowired(required = false)
    private VideoStatusTelemetry telemetry;

    /**
     * 从消息表中定期扫描待发送的消息并发布。
     *
     * <p>投递角色受两个开关约束：{@code async-enabled}（异步链路总开关，保持既有含义）
     * 与 {@code outbox-relay-enabled}（本实例是否承担投递）。任一为 false 时本方法直接返回，
     * 不领取、不恢复租约、不发布；消费者、批量落库与 dirty 恢复不受该开关影响，
     * 因此可以安全地把“停投”做成独立维护动作。</p>
     *
     * <p>T06 单轮预算：每轮最多处理 {@code outbox-batch-size} 条，并且在下一次领取之前
     * 检查 {@code round-budget-ms}。预算用尽就结束本轮，未领取的事件仍在 Outbox 表里，
     * 下一轮继续。预算只决定“还要不要拿新的一条”，已经开始的 publish 不会被强杀：
     * 它由 {@code publish-confirm-timeout-seconds} 界定，之后照常走 markSent / markPending。</p>
     *
     * <p>T07 异常隔离：单条事件的任何异常都在 {@link #publishOne} 内部被分类处理，
     * 外层再兜一层，保证一条坏消息不会中断本轮剩余事件；租约恢复失败只记日志、
     * 保留状态，本轮仍可安全尝试 claim；claim 自身失败则结束本轮。</p>
     */
    @Scheduled(
            fixedDelayString =
                    "${app.video-status.outbox-fixed-delay-ms:1000}",
            scheduler = VideoStatusSchedulingConfig.RELAY_SCHEDULER_BEAN
    )
    public void relay() {
        if (!isRelayRoleEnabled()) {
            return;
        }

        heartbeat(VideoStatusTelemetry.Operation.RELAY_TICK);
        recoverExpiredLease();

        long startedAt = System.nanoTime();
        long budgetNanos = budgetNanos();
        int maxPerRound = properties.getOutboxBatchSize();
        int processed = 0;

        for (int index = 0; index < maxPerRound; index++) {
            if (budgetExhausted(startedAt, budgetNanos)) {
                log.warn(
                        "视频统计 Outbox 投递达到单轮预算，本轮已处理 {} 条，"
                                + "剩余事件保留在 Outbox 表等待下一轮: budgetMs={}",
                        processed,
                        properties.getRoundBudgetMs()
                );
                break;
            }

            List<VideoStatusOutbox> claimed;
            try {
                claimed = claimService.claimBatch(CLAIM_ONE);
            } catch (RuntimeException e) {
                // 领取失败（DB 不可用等）：本轮结束，已领取的事件不会出现，下一轮再试。
                log.error("视频统计 Outbox 领取失败，结束本轮: reason={} class={}",
                        REASON_CLAIM_FAILED, e.getClass().getSimpleName());
                break;
            }
            if (claimed == null || claimed.isEmpty()) {
                // 没有待投递事件：本轮结束，不必把预算空转完。
                break;
            }
            for (VideoStatusOutbox outbox : claimed) {
                if (outbox == null) {
                    continue;
                }
                try {
                    publishOne(outbox);
                } catch (RuntimeException e) {
                    // publishOne 已按失败类型处理；这里只兜底，确保后续事件继续。
                    log.error("视频统计 Outbox 单条处理出现未分类异常，已隔离并继续本轮: "
                                    + "eventId={} reason={} class={}",
                            outbox.getEventId(),
                            REASON_ISOLATED_ROW_FAILURE,
                            e.getClass().getSimpleName());
                }
                processed++;
                if (Thread.currentThread().isInterrupted()) {
                    // confirm 被中断时 publisher 会保留中断标记：不再领取新工作，
                    // 结束本轮，避免在已中断的线程上继续做 JDBC/AMQP 调用。
                    log.warn("视频统计 Outbox 投递线程被中断，结束本轮，"
                            + "剩余事件保留在 Outbox 表等待下一轮: processed={}", processed);
                    return;
                }
            }
        }
    }

    private long budgetNanos() {
        return TimeUnit.MILLISECONDS.toNanos(properties.getRoundBudgetMs());
    }

    private boolean budgetExhausted(long startedAt, long budgetNanos) {
        return System.nanoTime() - startedAt >= budgetNanos;
    }

    /**
     * 处理单条 Outbox：解析/校验 → publish(confirm) → 回写。所有失败都在这里分类，
     * 不向调用方抛出业务异常（数据库二次回写失败也只记日志、保留 SENDING）。
     */
    private void publishOne(VideoStatusOutbox outbox) {
        long startedAt = System.nanoTime();
        String eventId = outbox.getEventId();
        String leaseToken = outbox.getLeaseToken();

        VideoStatusChangedEvent event;
        try {
            event = jsonUtils.fromJson(
                    outbox.getPayload(),
                    VideoStatusChangedEvent.class
            );
        } catch (RuntimeException e) {
            recordPublish(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            markInvalidFailed(outbox, REASON_PAYLOAD_JSON_INVALID, e);
            return;
        }
        if (event == null) {
            recordPublish(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            markInvalidFailed(outbox, REASON_PAYLOAD_EMPTY, null);
            return;
        }

        try {
            event.validate();
        } catch (RuntimeException e) {
            recordPublish(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            markInvalidFailed(outbox, REASON_EVENT_INVALID, e);
            return;
        }

        String mismatchReason = semanticMismatch(outbox, event);
        if (mismatchReason != null) {
            // 不改 eventId、不尝试修复：event 与 Outbox 行语义不一致属于数据损坏，
            // 继续发布只会把错误内容扩散到消费端。
            recordPublish(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            markInvalidFailed(outbox, mismatchReason, null);
            return;
        }

        try {
            publisher.publish(event, leaseToken);
        } catch (VideoStatusPublishException e) {
            recordPublish(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            handlePublishFailure(
                    outbox,
                    REASON_PUBLISH_PREFIX + e.reason().name(),
                    e
            );
            return;
        } catch (RuntimeException e) {
            recordPublish(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            handlePublishFailure(outbox, REASON_PUBLISH_UNKNOWN, e);
            return;
        }

        // 走到这里表示 confirm 已 ACK：指标按“发布成功”计，即使接下来回写失败。
        recordPublish(VideoStatusTelemetry.Outcome.SUCCESS, startedAt);
        handleConfirmed(outbox, eventId, leaseToken);
    }

    /**
     * confirm 成功后的回写。
     *
     * <p>markSent 抛异常时改用 {@code markConfirmedPending}：消息已经在 Broker 上，
     * 不能算发布失败；退回 PENDING 让 eventId 幂等吸收重复投递。
     * 若这次回写也失败，保留 SENDING 等租约恢复，不做任何其它状态修改。</p>
     */
    private void handleConfirmed(
            VideoStatusOutbox outbox,
            String eventId,
            String leaseToken
    ) {
        int rows;
        try {
            rows = outboxMapper.markSent(
                    eventId,
                    leaseToken,
                    LocalDateTime.now()
            );
        } catch (RuntimeException e) {
            log.error("视频统计 Outbox 已 confirm 但 markSent 回写失败，"
                            + "改为退回 PENDING 重投（不增加发布失败计数）: "
                            + "eventId={} reason={} class={}",
                    eventId,
                    REASON_CONFIRM_WRITEBACK_FAILED,
                    e.getClass().getSimpleName());
            markConfirmedPending(outbox, e);
            return;
        }
        if (rows == 1) {
            return;
        }
        diagnoseCas("markSent", outbox, rows);
    }

    /**
     * confirm 成功后的回写。
     *
     * <p>markSent 抛异常时改用 {@code markConfirmedPending}：消息已经在 Broker 上，
     * 不能算发布失败、更不能因一次 DB 抖动判 FAILED；退回 PENDING 让 eventId 幂等吸收重复投递。
     * 但这条路径必须有独立预算：每次失败递增 {@code confirm_writeback_count}，
     * 第 {@link #MAX_CONFIRM_WRITEBACK_RETRIES} 次仍回写失败就落 FAILED 终态，
     * 否则 markSent 永久异常而 markConfirmedPending 永久成功时，会每秒重投一次。</p>
     */
    private void markConfirmedPending(
            VideoStatusOutbox outbox,
            RuntimeException markSentFailure
    ) {
        String lastError = describe(REASON_CONFIRM_WRITEBACK_FAILED, markSentFailure);
        int confirmedWritebacks = outbox.getConfirmWritebackCount() == null
                ? 0
                : outbox.getConfirmWritebackCount();
        boolean budgetExhausted = confirmedWritebacks + 1
                >= MAX_CONFIRM_WRITEBACK_RETRIES;
        try {
            if (budgetExhausted) {
                int rows = outboxMapper.markConfirmWritebackExhausted(
                        outbox.getEventId(),
                        outbox.getLeaseToken(),
                        lastError
                );
                if (rows == 1) {
                    log.error("视频统计 Outbox confirm 成功但 markSent 回写持续失败，"
                                    + "独立回写预算已耗尽，已置 FAILED（待管理员审计处置）: "
                                    + "eventId={} confirmWritebackCount={} reason={}",
                            outbox.getEventId(), confirmedWritebacks,
                            REASON_CONFIRM_WRITEBACK_BUDGET_EXHAUSTED);
                    return;
                }
                diagnoseCas("markConfirmWritebackExhausted", outbox, rows);
                return;
            }

            int rows = outboxMapper.markConfirmedPending(
                    outbox.getEventId(),
                    outbox.getLeaseToken(),
                    LocalDateTime.now().plusSeconds(
                            CONFIRMED_WRITEBACK_RETRY_DELAY_SECONDS
                    ),
                    lastError
            );
            if (rows == 1) {
                log.warn("视频统计 Outbox 已 confirm，回写失败后已退回 PENDING "
                                + "等待重复投递（依赖 eventId 幂等）: eventId={} "
                                + "confirmWritebackCount={} reason={}",
                        outbox.getEventId(), confirmedWritebacks + 1,
                        REASON_CONFIRM_WRITEBACK_FAILED);
                return;
            }
            diagnoseCas("markConfirmedPending", outbox, rows);
        } catch (RuntimeException e) {
            // 二次回写也失败：保持 SENDING，等租约过期后由恢复流程接管。
            log.error("视频统计 Outbox 已 confirm 但二次回写失败，"
                            + "保留 SENDING 等待租约恢复，不做其它状态修改: "
                            + "eventId={} reason={} class={}",
                    outbox.getEventId(),
                    REASON_CONFIRM_WRITEBACK_PENDING_FAILED,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 发布失败（发送失败 / NACK / 不可路由 / confirm 不确定）：
     * 按 retry_count 做有界重试，耗尽后 markFailed 形成可处置终态。
     */
    private void handlePublishFailure(
            VideoStatusOutbox outbox,
            String reason,
            RuntimeException failure
    ) {
        String lastError = describe(reason, failure);
        int retryCount = outbox.getRetryCount() == null
                ? 0
                : outbox.getRetryCount();

        try {
            if (retryCount + 1 >= MAX_PUBLISH_RETRIES) {
                int rows = outboxMapper.markFailed(
                        outbox.getEventId(),
                        outbox.getLeaseToken(),
                        lastError
                );
                if (rows == 1) {
                    log.error("视频统计 Outbox 发布重试耗尽，已置 FAILED（待 T10 处置）: "
                                    + "eventId={} retryCount={} reason={}",
                            outbox.getEventId(), retryCount, reason);
                    return;
                }
                diagnoseCas("markFailed", outbox, rows);
                return;
            }

            long delaySeconds = retryDelaySeconds(retryCount);
            int rows = outboxMapper.markPending(
                    outbox.getEventId(),
                    outbox.getLeaseToken(),
                    LocalDateTime.now().plusSeconds(delaySeconds),
                    lastError
            );
            if (rows == 1) {
                log.warn("视频统计 Outbox 发布失败，将重试: eventId={} "
                                + "retryCount={} nextRetryInSeconds={} reason={}",
                        outbox.getEventId(), retryCount, delaySeconds, reason);
                return;
            }
            diagnoseCas("markPending", outbox, rows);
        } catch (RuntimeException e) {
            // 回写 PENDING/FAILED 失败：保留 SENDING 等租约恢复，本轮继续处理下一条。
            log.error("视频统计 Outbox 发布失败后回写状态也失败，"
                            + "保留 SENDING 等待租约恢复: eventId={} reason={} class={}",
                    outbox.getEventId(),
                    reason,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 非法 payload / 非法事件 / 语义不一致：不重试、不计发布失败次数，
     * 直接置 FAILED。租约 CAS 仍是唯一写入口，0 行只诊断。
     */
    private void markInvalidFailed(
            VideoStatusOutbox outbox,
            String reason,
            RuntimeException failure
    ) {
        String lastError = describe(reason, failure);
        try {
            int rows = outboxMapper.markInvalidFailed(
                    outbox.getEventId(),
                    outbox.getLeaseToken(),
                    lastError
            );
            if (rows == 1) {
                log.error("视频统计 Outbox 事件非法，已置 FAILED 且不再重试"
                                + "（待 T10 处置）: eventId={} reason={}",
                        outbox.getEventId(), reason);
                return;
            }
            diagnoseCas("markInvalidFailed", outbox, rows);
        } catch (RuntimeException e) {
            log.error("视频统计 Outbox 非法事件标记 FAILED 失败，"
                            + "保留 SENDING 等待租约恢复: eventId={} reason={} class={}",
                    outbox.getEventId(),
                    reason,
                    e.getClass().getSimpleName());
        }
    }

    /**
     * 所有 CAS 更新共用诊断：0 行 = 旧 owner/租约已失效，只记录、不再改状态；
     * 大于 1 行说明唯一键/租约条件被破坏，属于严重异常。
     */
    private void diagnoseCas(
            String operation,
            VideoStatusOutbox outbox,
            int rows
    ) {
        if (rows == 0) {
            log.warn("视频统计 Outbox 回写未命中（0 行），租约可能已过期或被新 owner 接管，"
                            + "不再修改状态: operation={} eventId={} leaseToken={}",
                    operation,
                    outbox.getEventId(),
                    outbox.getLeaseToken());
            return;
        }
        log.error("视频统计 Outbox 回写影响多行，CAS 条件异常，请立即排查: "
                        + "operation={} eventId={} leaseToken={} rows={}",
                operation,
                outbox.getEventId(),
                outbox.getLeaseToken(),
                rows);
    }

    /** event 与 Outbox 行的语义一致性；返回 null 表示一致。 */
    private String semanticMismatch(
            VideoStatusOutbox outbox,
            VideoStatusChangedEvent event
    ) {
        if (!Objects.equals(event.eventId(), outbox.getEventId())) {
            return REASON_EVENT_MISMATCH_EVENT_ID;
        }
        if (!Objects.equals(event.vid(), outbox.getAggregateId())) {
            return REASON_EVENT_MISMATCH_VID;
        }
        if (event.type() == null
                || outbox.getEventType() == null
                || !event.type().name().equals(outbox.getEventType())) {
            return REASON_EVENT_MISMATCH_TYPE;
        }
        return null;
    }

    private long retryDelaySeconds(int retryCount) {
        return Math.min(300, 1L << Math.min(Math.max(retryCount, 0), 8));
    }

    /**
     * last_error 只写 reason code + 异常类名，长度有界；绝不写异常 message
     * （Jackson/AMQP 的 message 可能带 payload 片段或连接信息）。
     */
    private String describe(String reason, RuntimeException failure) {
        String value = failure == null
                ? reason
                : reason + ":" + failure.getClass().getSimpleName();
        return value.length() <= MAX_LAST_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_LAST_ERROR_LENGTH);
    }

    private void heartbeat(VideoStatusTelemetry.Operation operation) {
        VideoStatusTelemetry target = telemetry;
        if (target == null) {
            return;
        }
        try {
            target.heartbeat(operation);
        } catch (RuntimeException ignored) {
            // 心跳失败不影响调度。
        }
    }

    private void recordPublish(
            VideoStatusTelemetry.Outcome outcome,
            long startedAt
    ) {
        VideoStatusTelemetry target = telemetry;
        if (target == null) {
            return;
        }
        try {
            target.record(
                    VideoStatusTelemetry.Operation.PUBLISH,
                    outcome,
                    System.nanoTime() - startedAt
            );
        } catch (RuntimeException ignored) {
            // 指标自身失败不得改变发布/重试/失败分类。
        }
    }

    private boolean isRelayRoleEnabled() {
        return properties.isAsyncEnabled()
                && properties.isOutboxRelayEnabled();
    }

    /**
     * 恢复已经超时的 Outbox 租约，避免消息一直卡在“发送中”的状态。
     *
     * <p>租约恢复的独立上限（{@code lease_recovery_count} 最大 10、1 秒重试）
     * 由 mapper/SQL 侧实现；本方法只负责调用。恢复失败不能静默丢弃：
     * 记录日志并保留 SENDING 状态，本轮仍可安全尝试 claim；若 claim 也失败，
     * {@link #relay()} 会结束本轮。</p>
     */
    private void recoverExpiredLease() {
        LocalDateTime expiredBefore = LocalDateTime.now()
                .minusSeconds(properties.getOutboxLeaseSeconds());
        try {
            outboxMapper.recoverExpiredSending(expiredBefore);
        } catch (RuntimeException e) {
            log.error("视频统计 Outbox 租约恢复失败，保留 SENDING 状态等待下一轮，"
                            + "本轮继续尝试领取: reason={} class={}",
                    REASON_LEASE_RECOVERY_FAILED,
                    e.getClass().getSimpleName());
        }
    }
}
