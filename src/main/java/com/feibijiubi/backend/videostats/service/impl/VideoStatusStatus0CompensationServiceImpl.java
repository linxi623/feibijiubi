package com.feibijiubi.backend.videostats.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.videostats.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.videostats.mq.VideoStatusEventPublisher;
import com.feibijiubi.backend.videostats.service.VideoStatusEventAgeGuard;
import com.feibijiubi.backend.videostats.service.VideoStatusEventFingerprintService;
import com.feibijiubi.backend.videostats.service.VideoStatusStatus0CompensationService;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * T09 第一阶段实现。关键边界：
 *
 * <ul>
 *   <li>领取事务只覆盖 DB 租约 CAS；Redis 证据检查与 MQ confirm 都在事务外；</li>
 *   <li>不使用新 eventId，payload/指纹/DB 年龄/occurredAt 年龄任一不符即释放租约并冷却；</li>
 *   <li>只有 processed Key 存在且剩余 TTL 足以覆盖 MQ 最大重试退避和租约/confirm
 *       安全余量时才按“已证明应用”发送：再次投递会被 Lua 判 DUPLICATE，不会重复累计；</li>
 *   <li>processed 缺失或 TTL 不足时默认既不发送也不置 status3：可能未应用，也可能 current
 *       已应用而 processed 单独丢失，证据不确定就只诊断，交 T10/T12；</li>
 *   <li>只有 enabled + integrityConfirmed + allowUnprovenReplay 三闸门同时为真时，
 *       才在安全年龄内对缺失 processed 的原 eventId 做受控重放；缺失仍不等于“未应用”；</li>
 *   <li>超过安全窗口只计数告警，不重发；达到尝试上限后同样只保留 RECEIVED 供 preview。</li>
 * </ul>
 */
@Slf4j
@Service
public class VideoStatusStatus0CompensationServiceImpl
        implements VideoStatusStatus0CompensationService {

    private static final long EVENT_FUTURE_TOLERANCE_SECONDS = 300L;
    private static final int MAX_ERROR_LENGTH = 1000;

    static final String REASON_PAYLOAD_INVALID = "STATUS0_PAYLOAD_INVALID";
    static final String REASON_EVENT_INVALID = "STATUS0_EVENT_INVALID";
    static final String REASON_EVENT_MISMATCH = "STATUS0_EVENT_MISMATCH";
    static final String REASON_FINGERPRINT_MISMATCH = "STATUS0_FINGERPRINT_MISMATCH";
    static final String REASON_OVER_SAFE_WINDOW = "STATUS0_OVER_SAFE_WINDOW";
    static final String REASON_PROCESSED_MISSING = "STATUS0_PROCESSED_EVIDENCE_MISSING";
    static final String REASON_PROCESSED_TTL_INSUFFICIENT =
            "STATUS0_PROCESSED_TTL_INSUFFICIENT";
    static final String REASON_PROCESSED_TTL_UNKNOWN =
            "STATUS0_PROCESSED_TTL_UNKNOWN";
    static final String REASON_EVIDENCE_CHECK_FAILED =
            "STATUS0_EVIDENCE_CHECK_FAILED";
    static final String REASON_UNPROVEN_REPLAY = "STATUS0_UNPROVEN_REPLAY_AUTHORIZED";
    static final String REASON_PUBLISH_FAILED = "STATUS0_PUBLISH_FAILED";
    static final String REASON_RELEASE_FAILED = "STATUS0_RELEASE_FAILED";

    private final VideoStatusConsumedEventMapper consumedEventMapper;
    private final VideoStatusStatus0CompensationClaimService claimService;
    private final VideoStatusEventPublisher publisher;
    private final VideoStatusEventFingerprintService fingerprintService;
    private final StringRedisTemplate stringRedisTemplate;
    private final VideoStatusProperties properties;
    private final ObjectMapper objectMapper;
    /** 可注入的单调时钟，仅用于单轮时长预算，不参与任何业务年龄判断。 */
    private final LongSupplier nanoTime;

    @Autowired
    public VideoStatusStatus0CompensationServiceImpl(
            VideoStatusConsumedEventMapper consumedEventMapper,
            VideoStatusStatus0CompensationClaimService claimService,
            VideoStatusEventPublisher publisher,
            VideoStatusEventFingerprintService fingerprintService,
            StringRedisTemplate stringRedisTemplate,
            VideoStatusProperties properties,
            ObjectMapper objectMapper
    ) {
        this(consumedEventMapper, claimService, publisher, fingerprintService,
                stringRedisTemplate, properties, objectMapper, System::nanoTime);
    }

    /** 测试构造：注入确定性单调时钟，不改变生产默认的 System.nanoTime。 */
    public VideoStatusStatus0CompensationServiceImpl(
            VideoStatusConsumedEventMapper consumedEventMapper,
            VideoStatusStatus0CompensationClaimService claimService,
            VideoStatusEventPublisher publisher,
            VideoStatusEventFingerprintService fingerprintService,
            StringRedisTemplate stringRedisTemplate,
            VideoStatusProperties properties,
            ObjectMapper objectMapper,
            LongSupplier nanoTime
    ) {
        this.consumedEventMapper = consumedEventMapper;
        this.claimService = claimService;
        this.publisher = publisher;
        this.fingerprintService = fingerprintService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public PreviewResult preview(int limit, Long afterId) {
        requireLimit(limit);
        List<VideoStatusConsumedEvent> rows =
                consumedEventMapper.selectStatus0CompensationPreview(afterId, limit);
        long maxAge = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        int countCap = properties.getStatus0CompensationOverSafeWindowCountCap();
        long overSafeWindow = consumedEventMapper.countStatus0OverSafeWindow(
                maxAge, countCap);
        // occurredAt 与 runOnce 一样用 JVM 时钟比较（payload 由生产端 JVM 写入），
        // 整轮只取一次时间，判定内部不再重复取。
        LocalDateTime ageNow = LocalDateTime.now();
        List<PreviewItem> items = new ArrayList<>(rows.size());
        for (VideoStatusConsumedEvent row : rows) {
            Long age = row.getConsumedAgeSeconds();
            // 所有租约/冷却/静默期比较统一使用同一条 SQL 返回的 DB 当前时间。
            LocalDateTime now = row.getDatabaseNow();
            boolean dbNowKnown = now != null;
            boolean withinGrace = dbNowKnown && row.getLastAttemptAt() != null
                    && row.getLastAttemptAt().isBefore(now.minusSeconds(
                    properties.getStatus0CompensationMinAgeSeconds()));
            boolean leaseFree = row.getCompensationLeaseUntil() == null
                    || (dbNowKnown
                    && !row.getCompensationLeaseUntil().isAfter(now));
            boolean cooldownElapsed = row.getCompensationNextAttemptAt() == null
                    || (dbNowKnown
                    && !row.getCompensationNextAttemptAt().isAfter(now));
            int attempts = row.getCompensationAttempts() == null
                    ? 0 : row.getCompensationAttempts();
            boolean attemptsRemaining =
                    attempts < properties.getStatus0CompensationMaxAttempts();
            // 与 compensateOne 共用同一判定：preview 不给出写侧会拒绝的 eligible。
            Status0Verdict verdict = evaluate(row, ageNow);
            items.add(new PreviewItem(
                    row.getEventId(),
                    row.getVid(),
                    row.getEventType(),
                    row.getProcessStatus() == null ? -1 : row.getProcessStatus(),
                    age,
                    attempts,
                    row.getLastAttemptAt(),
                    row.getCompensationNextAttemptAt(),
                    row.getCompensationLeaseUntil(),
                    withinGrace,
                    verdict.withinSafeWindow(),
                    leaseFree,
                    cooldownElapsed,
                    attemptsRemaining,
                    withinGrace && verdict.eligible() && leaseFree
                            && cooldownElapsed && attemptsRemaining,
                    VideoStatusStatus0CompensationService.EVIDENCE_UNKNOWN,
                    previewDecisionGate(),
                    verdict.refusalCode()
            ));
        }
        return new PreviewResult(
                overSafeWindow,
                overSafeWindow >= countCap,
                properties.isStatus0CompensationEnabled(),
                items
        );
    }

    @Override
    public RoundResult runOnce(Long afterId) {
        long maxAge = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        int countCap = properties.getStatus0CompensationOverSafeWindowCountCap();
        long overSafeWindow = consumedEventMapper.countStatus0OverSafeWindow(
                maxAge, countCap);
        boolean overSafeWindowLowerBound = overSafeWindow >= countCap;
        if (overSafeWindow > 0) {
            log.warn("视频统计 status0 存在超过安全窗口的事件，未自动重发，"
                            + "保留 RECEIVED 等待 T10/T12 诊断: count{}={} maxAgeSeconds={}",
                    overSafeWindowLowerBound ? "AtLeast" : "",
                    overSafeWindow,
                    maxAge);
        }

        if (!properties.isStatus0CompensationEnabled()) {
            return new RoundResult(0, 0, 0, 0, 0, overSafeWindow,
                    overSafeWindowLowerBound, null);
        }
        if (!properties.isStatus0CompensationIntegrityConfirmed()) {
            // 启动校验已拒绝该组合；这里是防御式二次闸门。
            return new RoundResult(0, 0, 0, 0, 0, overSafeWindow,
                    overSafeWindowLowerBound, null);
        }

        int batchSize = properties.getStatus0CompensationBatchSize();
        Long cursor = afterId;
        int scanned = 0;
        int claimedCount = 0;
        int republished = 0;
        int unprovenRepublished = 0;
        int noEvidence = 0;
        int released = 0;
        long roundStartNanos = nanoTime.getAsLong();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(
                properties.getRoundBudgetMs());
        boolean stoppedEarly = false;
        while (scanned < batchSize) {
            // 每领取一条前都重新检查闸门、线程中断与单轮时长预算。
            // 已领取/已在处理中的一条不会被强杀，由 confirm timeout 与 SQL 超时界定。
            if (!properties.isStatus0CompensationEnabled()
                    || !properties.isStatus0CompensationIntegrityConfirmed()) {
                stoppedEarly = true;
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                stoppedEarly = true;
                break;
            }
            if (nanoTime.getAsLong() - roundStartNanos >= budgetNanos) {
                stoppedEarly = true;
                break;
            }
            VideoStatusStatus0CompensationClaimService.ClaimBatch batch =
                    claimService.claimNext(cursor);
            if (batch.lastScannedId() == null) {
                cursor = null;
                break;
            }
            // 关键：keyset 按“实际扫描到的最后 id”推进，不按 CAS 成功数。
            // CAS 冲突的条目本轮跳过后，下一轮仍会从尾部继续，不会回头饿死后续事件。
            cursor = batch.lastScannedId();
            scanned++;
            if (batch.claimed().isEmpty()) {
                continue;
            }
            claimedCount++;
            Outcome outcome = compensateOne(batch.claimed().get(0));
            switch (outcome) {
                case REPUBLISHED -> republished++;
                case UNPROVEN_REPUBLISHED -> {
                    republished++;
                    unprovenRepublished++;
                }
                case NO_PROCESSED_EVIDENCE -> noEvidence++;
                case RELEASED -> released++;
                default -> {
                }
            }
        }
        // 预算/中断/闸门提前停止时保留实际已扫描到的 cursor，下一轮从断点继续，
        // 而不是重置为 null 从头部重扫造成尾部饥饿。只有真正扫空才回到起点。
        Long nextCursor = stoppedEarly
                ? cursor
                : (scanned >= batchSize ? cursor : null);
        return new RoundResult(
                claimedCount,
                republished,
                unprovenRepublished,
                noEvidence,
                released,
                overSafeWindow,
                overSafeWindowLowerBound,
                nextCursor
        );
    }

    private Outcome compensateOne(VideoStatusConsumedEvent row) {
        String token = row.getCompensationLeaseToken();
        try {
            // 与 preview 共用同一判定：同一套拒绝原因码，且不在这里重复取时间。
            Status0Verdict verdict = evaluate(row, LocalDateTime.now());
            if (!verdict.eligible()) {
                release(row, token, verdict.refusalCode());
                return Outcome.RELEASED;
            }
            VideoStatusChangedEvent event = verdict.event();
            String actualHash = fingerprintService.hash(event);
            if (!Objects.equals(actualHash, row.getPayloadHash())) {
                release(row, token, REASON_FINGERPRINT_MISMATCH);
                return Outcome.RELEASED;
            }

            // 只读证据检查，不在领取事务内。这里必须读 TTL 而不只是 hasKey：
            // Key 当前存在但剩余寿命不足以覆盖 MQ 最大重试退避时，重试期间
            // processed 可能先过期，消费者会再次累计。
            Long processedTtlSeconds;
            try {
                processedTtlSeconds = stringRedisTemplate.getExpire(
                        RedisKeyUtils.processedKey(event.eventId()),
                        TimeUnit.SECONDS
                );
            } catch (RuntimeException e) {
                release(row, token, REASON_EVIDENCE_CHECK_FAILED + ":"
                        + e.getClass().getSimpleName());
                return Outcome.RELEASED;
            }
            if (processedTtlSeconds == null || processedTtlSeconds == -1L) {
                // null = 无法判断；-1 = 无过期时间，无法证明能覆盖重试窗口。
                release(row, token, REASON_PROCESSED_TTL_UNKNOWN);
                return Outcome.NO_PROCESSED_EVIDENCE;
            }
            boolean unproven = false;
            if (processedTtlSeconds == -2L) {
                // 缺失不等于未应用：只有操作员显式开启受控恢复模式才允许重放。
                if (!properties.isStatus0CompensationAllowUnprovenReplay()) {
                    release(row, token, REASON_PROCESSED_MISSING);
                    return Outcome.NO_PROCESSED_EVIDENCE;
                }
                unproven = true;
            } else if (processedTtlSeconds
                    < properties.getStatus0CompensationRequiredProcessedTtlSeconds()) {
                // 剩余不足时即使 occurredAt/DB 年龄仍在窗口内也拒发。
                release(row, token, REASON_PROCESSED_TTL_INSUFFICIENT);
                return Outcome.NO_PROCESSED_EVIDENCE;
            }

            try {
                publisher.publish(event, token);
            } catch (RuntimeException e) {
                release(row, token,
                        REASON_PUBLISH_FAILED + ":" + e.getClass().getSimpleName());
                return Outcome.RELEASED;
            }

            int updated = consumedEventMapper.markStatus0CompensationRepublished(
                    event.eventId(), token
            );
            if (updated != 1) {
                // 发送成功但消费者已推进状态/租约被接管：不做任何补偿性改状态。
                log.warn("视频统计 status0 已重投但回写未命中，事件状态可能已被消费者推进: "
                                + "eventId={} rows={}", event.eventId(), updated);
            }
            if (unproven) {
                log.warn("视频统计 status0 在 allow-unproven-replay 受控模式下重投了"
                                + " processed 证据缺失的事件，缺失未被当作未应用: eventId={} reason={}",
                        event.eventId(), REASON_UNPROVEN_REPLAY);
                return Outcome.UNPROVEN_REPUBLISHED;
            }
            return Outcome.REPUBLISHED;
        } catch (RuntimeException e) {
            String reason = e.getMessage() != null
                    && e.getMessage().startsWith("STATUS0_")
                    ? e.getMessage()
                    : REASON_RELEASE_FAILED + ":" + e.getClass().getSimpleName();
            release(row, token, reason);
            return Outcome.RELEASED;
        }
    }

    private VideoStatusChangedEvent parseAndMatch(VideoStatusConsumedEvent row) {
        VideoStatusChangedEvent event;
        try {
            event = objectMapper.readValue(
                    row.getPayload(), VideoStatusChangedEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(REASON_PAYLOAD_INVALID);
        }
        try {
            event.validate();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(REASON_EVENT_INVALID);
        }
        if (!Objects.equals(row.getEventId(), event.eventId())
                || !Objects.equals(row.getVid(), event.vid())
                || !Objects.equals(row.getDelta(), event.delta())
                || event.type() == null
                || !Objects.equals(row.getEventType(), event.type().name())) {
            throw new IllegalArgumentException(REASON_EVENT_MISMATCH);
        }
        return event;
    }

    /**
     * parseAndMatch 只抛这三个固定安全码；任何其它文本（包括未来改动引入的异常消息）都不回显到
     * preview 响应和 release 的 last_error，统一回落 {@link #REASON_EVENT_INVALID}。
     */
    private static String parseFailureReason(String message) {
        if (REASON_PAYLOAD_INVALID.equals(message)
                || REASON_EVENT_INVALID.equals(message)
                || REASON_EVENT_MISMATCH.equals(message)) {
            return message;
        }
        return REASON_EVENT_INVALID;
    }

    /**
     * 唯一的年龄/内容判定：preview 与 runOnce 共用，读写两侧给同一个原因码。
     *
     * <p>只读：不领取租约、不写 DB、不写 Redis。判定顺序与既有写侧完全一致
     * （payload/事件一致性 → DB 年龄 → occurredAt 未来/安全窗口 → 固定 30 天上界），
     * 因此默认 7 天窗口下超龄事件仍然给出 {@code STATUS0_OVER_SAFE_WINDOW}，
     * 新的固定上界只在运维把窗口调到 30 天以上时才生效。{@code now} 由调用方取一次
     * （JVM 时钟，与既有 occurredAt 比较口径一致），本方法不再取时间。</p>
     */
    private Status0Verdict evaluate(VideoStatusConsumedEvent row, LocalDateTime now) {
        long maxAge = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        Long age = row.getConsumedAgeSeconds();
        boolean withinDbWindow = age != null && age >= 0 && age < maxAge;
        VideoStatusChangedEvent event;
        try {
            event = parseAndMatch(row);
        } catch (IllegalArgumentException e) {
            // payload/账本内容无法验证时 occurredAt 也无从证明，因此 withinSafeWindow 必须为 false，
            // 不能只凭 DB 年龄就声明“DB 年龄 + occurredAt 窗口都成立”（与接口/文档语义一致）。
            return new Status0Verdict(false, null, parseFailureReason(e.getMessage()));
        }
        if (!withinDbWindow) {
            return new Status0Verdict(false, event, REASON_OVER_SAFE_WINDOW);
        }
        LocalDateTime occurredAt = event.occurredAt();
        if (occurredAt.isAfter(now.plusSeconds(EVENT_FUTURE_TOLERANCE_SECONDS))
                || !occurredAt.isAfter(now.minusSeconds(maxAge))) {
            return new Status0Verdict(false, event, REASON_OVER_SAFE_WINDOW);
        }
        // T14-S2 固定政策上界（30 天）：maxAge 只要求小于 redisEventTtlDays，可以被调到
        // 30 天以上；这里只兜底“窗口被调大”的场景，且在任何发布/状态写之前。
        if (VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(occurredAt, now)) {
            return new Status0Verdict(false, event, VideoStatusEventAgeGuard.REFUSAL_CODE);
        }
        return new Status0Verdict(true, event, null);
    }

    private void release(
            VideoStatusConsumedEvent row,
            String token,
            String reason
    ) {
        try {
            int rows = consumedEventMapper.releaseStatus0Compensation(
                    row.getEventId(),
                    token,
                    properties.getStatus0CompensationCooldownSeconds(),
                    truncate(reason)
            );
            if (rows != 1) {
                log.warn("视频统计 status0 补偿释放租约未命中（可能已被接管）: "
                                + "eventId={} reason={}", row.getEventId(), reason);
            }
        } catch (RuntimeException e) {
            log.error("视频统计 status0 补偿释放租约失败，等待租约自然过期: "
                            + "eventId={} reason={} class={}",
                    row.getEventId(), reason, e.getClass().getSimpleName());
        }
    }

    private String previewDecisionGate() {
        if (!properties.isStatus0CompensationEnabled()) {
            return VideoStatusStatus0CompensationService.GATE_MASTER_SWITCH_OFF;
        }
        if (!properties.isStatus0CompensationIntegrityConfirmed()) {
            return VideoStatusStatus0CompensationService.GATE_INTEGRITY_NOT_CONFIRMED;
        }
        return properties.isStatus0CompensationAllowUnprovenReplay()
                ? VideoStatusStatus0CompensationService.GATE_ALLOW_UNPROVEN_REPLAY
                : VideoStatusStatus0CompensationService.GATE_PROVEN_PROCESSED_ONLY;
    }

    private void requireLimit(int limit) {
        if (limit <= 0 || limit > VideoStatusProperties.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "preview limit 必须在 1 到 "
                            + VideoStatusProperties.MAX_BATCH_SIZE
                            + " 之间，当前值：" + limit
            );
        }
    }

    private String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_LENGTH);
    }

    private enum Outcome {
        REPUBLISHED,
        UNPROVEN_REPUBLISHED,
        NO_PROCESSED_EVIDENCE,
        RELEASED
    }

    /**
     * 年龄/内容判定结果：preview 与 runOnce 共用。
     *
     * <p>{@code withinSafeWindow} 表示 DB 年龄与 occurredAt 都落在可配置安全窗口内；payload/账本内容
     * 无法验证时为 {@code false}（此时 occurredAt 无从证明）。{@code refusalCode} 为 {@code null} 时
     * {@code eligible()} 为真。{@code event} 只在内部用于指纹与发布，绝不进入 preview 响应。</p>
     */
    private record Status0Verdict(
            boolean withinSafeWindow,
            VideoStatusChangedEvent event,
            String refusalCode
    ) {
        boolean eligible() {
            return refusalCode == null;
        }
    }
}
