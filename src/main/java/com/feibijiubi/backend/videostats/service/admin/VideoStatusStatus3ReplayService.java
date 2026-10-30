package com.feibijiubi.backend.videostats.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.entity.VideoStatusConsumptionRepairLog;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.videostats.mapper.VideoStatusFailureAdminMapper;
import com.feibijiubi.backend.videostats.service.admin.VideoStatusStatus3ReplayRow.ConsumedTarget;
import com.feibijiubi.backend.videostats.service.admin.VideoStatusStatus3ReplayRow.OutboxSource;
import com.feibijiubi.backend.videostats.service.VideoStatusEventAgeGuard;
import com.feibijiubi.backend.videostats.service.VideoStatusEventFingerprintService;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.videostats.vo.VideoStatusStatus3ReplayVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * T10-B1：<b>consumed status3 单事件、proven-only、受审计的受控重放</b>。
 *
 * <h2>只做一件事</h2>
 * <p>把一个 {@code video_status_consumed_event.process_status=3 (REPAIR_REQUIRED)} 且能
 * <b>证明 Redis 已应用过</b>的消费账本行重置为 {@code 0 (RECEIVED)}，并把同一 eventId 的
 * Outbox 行从 {@code 2 (SENT)} 退回 {@code 0 (PENDING)}，在同一 MySQL 事务里写一条
 * {@code COMPLETED} 审计。提交后由既有 Outbox relay 领取/confirm/租约恢复投递同一
 * eventId、同一 payload。</p>
 *
 * <h2>为什么必须 proven</h2>
 * <p>{@code redis_applied_at IS NULL} 有两种可能：从未应用（真正的 RECEIVED 超龄），
 * 或已应用但 {@code markRedisApplied} 回写丢失。只有 Redis 的 processed 幂等 Key 能区分
 * 二者。本接口只在 processed Key 存在、且剩余 TTL 足以覆盖“MQ 最大重试退避 + 投递租约/
 * confirm 余量”时才重放：此时重复投递会被增量 Lua 判为 {@code DUPLICATE}，不会二次累计。
 * processed 缺失（{@code -2}）/无过期（{@code -1}）/无法读取一律拒绝，<b>绝不</b>把缺失
 * 当作“未应用”。因此本接口<b>没有</b> unproven 模式：它不覆盖任意 Redis 数据丢失，
 * 也不是 T09 三闸门中“受控未证明重放”的替代入口。</p>
 *
 * <h2>为什么区分刷库型 status3</h2>
 * <p>{@code redis_applied_at} 非空表示 Redis 已累计、只是批量落库失败（
 * {@code markFlushRepairRequired} 1→3）。这类事件重置为 RECEIVED 后重投只会被判
 * {@code DUPLICATE}，无法补上 DB 落库，还会反复回到 status3。它必须走刷库重试或
 * 门闩内的重建（T12），本接口一律 409 拒绝。</p>
 *
 * <h2>投递可靠性</h2>
 * <p>本类<b>不</b>在事务内 publish，也<b>不</b>在提交后由本线程发送：那两种做法都没有
 * “提交后进程崩溃仍会重发”的保证，且事务内等待 confirm 会长时间持有行锁。这里只改 DB：
 * Outbox 行退回 PENDING 后由既有 relay（领取 + publisher confirm + 失败退避 +
 * 租约恢复）负责投递，语义与业务写入完全一致。前提是 Outbox 行必须存在且为 SENT；
 * 缺失或处于其它状态时拒绝重放，而不是降级成“仅内存发送”。</p>
 *
 * <h2>安全不变量</h2>
 * <ol>
 *   <li>不新建 eventId、不改 payload、不改 outbox 的三份历史预算、不清零
 *       {@code consumer_retry_count}、不刷新 {@code consumed_at}（事件年龄不变）、
 *       不删除 {@code last_error} 失败证据。</li>
 *   <li>状态写、Outbox 退回与审计插入在同一事务：任何一步影响行数不为 1 或抛异常都整体回滚，
 *       不允许出现“状态改了但没有独立审计”。</li>
 *   <li>拒绝条件在写之前求值，且与 {@code GET .../precheck?source=CONSUMED} 共用同一套
 *       判定，读侧不会给出写侧会拒绝的“可重放”。其中 {@code occurredAt} 还受 T14-S2 的
 *       固定 30 天上界约束（{@code EVENT_OVER_NORMAL_REPLAY_WINDOW}）；默认 7 天窗口更严，
 *       所以默认配置下的超龄事件仍返回 {@code CONSUMED_OVER_SAFE_WINDOW}。</li>
 *   <li>HTTP 只返回安全字段：不含 payload、lease token 或 {@code last_error} 原文。</li>
 *   <li>重放次数按审计行有界计数，达到 {@code status3-replay-max-attempts} 后拒绝，
 *       避免“重放→再入 status3→再重放”的人工死循环。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class VideoStatusStatus3ReplayService {

    /** 审计动作名，写入 {@code video_status_consumption_repair_log.action}（<=32 字符）。 */
    public static final String ACTION_REPLAY_TO_RECEIVED = "REPLAY_CONSUMED_TO_RECEIVED";

    /** 写入 {@code repair_log.last_error} 的操作元数据版本，供 duplicate 重放原始 before/after。 */
    static final String AUDIT_METADATA_SCHEMA = "T10_STATUS3_REPLAY_META_V1";

    public static final String DECISION_PROVEN_REPLAY_ELIGIBLE = "PROVEN_REPLAY_ELIGIBLE";
    public static final String DECISION_REFUSED = "REFUSED_CONTROLLED_REPAIR_ONLY";

    /** 投递方式：本接口只把消息交给既有 Outbox relay，不由请求线程发送。 */
    static final String DELIVERY_MODE_OUTBOX_RELAY = "OUTBOX_PENDING_RELAY";

    public static final String REFUSAL_TARGET_NOT_REPAIR_REQUIRED =
            "CONSUMED_TARGET_NOT_REPAIR_REQUIRED";
    public static final String REFUSAL_FLUSH_ORIGIN_NOT_REPLAYABLE =
            "CONSUMED_STATUS3_FLUSH_ORIGIN_NOT_REPLAYABLE";
    public static final String REFUSAL_OVER_SAFE_WINDOW = "CONSUMED_OVER_SAFE_WINDOW";
    public static final String REFUSAL_OUTBOX_LEDGER_MISSING = "CONSUMED_OUTBOX_LEDGER_MISSING";
    public static final String REFUSAL_OUTBOX_NOT_SENT = "CONSUMED_OUTBOX_NOT_SENT";
    public static final String REFUSAL_PAYLOAD_INVALID = "CONSUMED_PAYLOAD_INVALID";
    public static final String REFUSAL_EVENT_INVALID = "CONSUMED_EVENT_INVALID";
    public static final String REFUSAL_EVENT_MISMATCH = "CONSUMED_EVENT_MISMATCH";
    public static final String REFUSAL_FINGERPRINT_MISMATCH = "CONSUMED_FINGERPRINT_MISMATCH";
    public static final String REFUSAL_PROCESSED_EVIDENCE_MISSING =
            "CONSUMED_PROCESSED_EVIDENCE_MISSING";
    public static final String REFUSAL_PROCESSED_TTL_UNKNOWN = "CONSUMED_PROCESSED_TTL_UNKNOWN";
    public static final String REFUSAL_PROCESSED_TTL_INSUFFICIENT =
            "CONSUMED_PROCESSED_TTL_INSUFFICIENT";
    public static final String REFUSAL_EVIDENCE_CHECK_FAILED = "CONSUMED_EVIDENCE_CHECK_FAILED";
    public static final String REFUSAL_REPLAY_BUDGET_EXHAUSTED = "CONSUMED_REPLAY_BUDGET_EXHAUSTED";

    /** 允许生产端时钟轻微超前；超过即视为未来事件，拒绝（与 T07/T09 同一容忍度）。 */
    private static final long EVENT_FUTURE_TOLERANCE_SECONDS = 300L;

    private static final int CONSUMED_REPAIR_REQUIRED = 3;
    private static final int OUTBOX_STATUS_SENT = 2;
    private static final int REPAIR_LOG_COMPLETED = 1;

    private final VideoStatusFailureAdminMapper mapper;
    private final VideoStatusProperties properties;
    private final ObjectMapper objectMapper;
    private final VideoStatusEventFingerprintService fingerprintService;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 只读预检查：与 {@link #replay} 共用同一套拒绝判定。
     *
     * <p>只有 {@code process_status=3} 才返回重放子结构；status0 仍是 T10 第一阶段的只读诊断，
     * 其它状态由失败列表/详情按 404 处理。本方法不领取、不改状态、不发送消息，唯一的 I/O 是
     * 一次只读 Redis {@code GETEXPIRE}（processed 证据），异常时返回
     * {@link #REFUSAL_EVIDENCE_CHECK_FAILED}，绝不 fail-open。</p>
     */
    @Transactional(readOnly = true)
    public VideoStatusFailureAdminService.ReplayPrecheck precheckStatus3Replay(String eventId) {
        ConsumedTarget target = mapper.selectStatus3ReplayTarget(eventId);
        if (target == null
                || !Integer.valueOf(CONSUMED_REPAIR_REQUIRED).equals(target.getProcessStatus())) {
            return null;
        }
        OutboxSource outbox = mapper.selectOutboxReplaySource(eventId);
        Evaluation evaluation = evaluate(target, outbox, countReplayAttempts(eventId));
        return new VideoStatusFailureAdminService.ReplayPrecheck(
                evaluation.decision(), evaluation.eligible(), evaluation.refusalCode(),
                evaluation.redisAppliedEvidencePresent(), evaluation.withinSafeWindow(),
                evaluation.consumedAgeSeconds(), evaluation.outboxStatus(),
                evaluation.processedTtlSeconds(), evaluation.replayAttempts(),
                evaluation.maxReplayAttempts());
    }

    /**
     * 受审计受控重放：consumed status3 → RECEIVED + Outbox SENT → PENDING + COMPLETED 审计，
     * 三条写操作在同一事务内，任一失败整体回滚。所有拒绝都发生在写之前，拒绝请求不留下审计行。
     */
    @Transactional(rollbackFor = Exception.class)
    public VideoStatusStatus3ReplayVO replay(String source, String eventId, String operationId,
                                             String reason, String operator) {
        requireConsumedSource(source);
        requireEventId(eventId);
        requireAuditFields(operationId, reason, operator);

        // 1) 目标行锁：同一 eventId 的重放请求在 DB 层串行。
        ConsumedTarget target = mapper.selectStatus3ReplayTargetForUpdate(eventId);
        if (target == null) {
            throw new BusinessException(404,
                    "没有找到该 eventId 的消费账本行（source=CONSUMED 只能重放已登记的消费事件）");
        }

        // 2) operationId 幂等：行锁之后再读审计，同目标并发请求会被吸收为 duplicate。
        VideoStatusConsumptionRepairLog existing = mapper.selectRepairLogByOperationId(operationId);
        if (existing != null) {
            return duplicateResult(existing, target, eventId, reason, operator);
        }

        // 3) 拒绝矩阵（与预检查同一套判定）。processed 证据的 Redis 读取是单条有界只读命令，
        //    不涉及 MQ confirm，也不会长时间占用行锁。
        OutboxSource outbox = mapper.selectOutboxReplaySourceForUpdate(eventId);
        Evaluation evaluation = evaluate(target, outbox, countReplayAttempts(eventId));
        if (!evaluation.eligible()) {
            throw rejection(evaluation);
        }

        // 4) CAS 目标行：只有 3 + redis_applied_at IS NULL + 同一 payload_hash 才命中。
        int consumedUpdated = mapper.resetConsumedRepairToReceivedForReplay(
                eventId, target.getPayloadHash());
        if (consumedUpdated != 1) {
            throw new BusinessException(409,
                    "consumed 状态已被并发修改（CAS 影响行数=" + consumedUpdated
                            + "），本次重放整体回滚，请重新执行 precheck 后决定");
        }

        // 5) 同一事务把 Outbox 退回 PENDING：投递交给既有 relay，不在事务内 publish。
        int outboxUpdated = mapper.resetOutboxSentToPendingForReplay(eventId);
        if (outboxUpdated != 1) {
            throw new BusinessException(409,
                    "Outbox 投递源状态已变化（CAS 影响行数=" + outboxUpdated
                            + "），本次重放整体回滚；请确认该 eventId 的 Outbox 行仍为 SENT 后重试");
        }

        // 6) 同事务写 COMPLETED 审计（before/after 进 last_error 元数据，供 duplicate 复原）。
        int replayAttemptNo = evaluation.replayAttempts() + 1;
        VideoStatusConsumptionRepairLog log = new VideoStatusConsumptionRepairLog();
        log.setOperationId(operationId);
        log.setEventId(eventId);
        log.setAction(ACTION_REPLAY_TO_RECEIVED);
        log.setReason(reason);
        log.setOperator(operator);
        log.setLastError(auditMetadataJson(target, evaluation, replayAttemptNo));
        int inserted;
        try {
            inserted = mapper.insertCompletedRepairLog(log);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409,
                    "审计行唯一键冲突（operationId 已被其它处置请求使用），本次重放整体回滚，"
                            + "不重复执行动作；请用 new operationId 或 original operationId 查询");
        }
        if (inserted != 1) {
            throw new BusinessException(500,
                    "审计写入影响行数异常（" + inserted + "），本次重放整体回滚");
        }

        VideoStatusConsumptionRepairLog persisted = mapper.selectRepairLogByOperationId(operationId);
        if (persisted == null || persisted.getCompletedAt() == null) {
            throw new BusinessException(500,
                    "审计写入后无法读取 DB completed_at，本次重放整体回滚");
        }

        VideoStatusStatus3ReplayVO vo = new VideoStatusStatus3ReplayVO();
        vo.setOperationId(operationId);
        vo.setAction(ACTION_REPLAY_TO_RECEIVED);
        vo.setSource(VideoStatusFailureAdminService.SOURCE_CONSUMED);
        vo.setEventId(eventId);
        vo.setVid(target.getVid());
        vo.setEventType(target.getEventType());
        vo.setOperator(operator);
        vo.setOperationStatus("COMPLETED");
        vo.setCompletedAt(persisted.getCompletedAt());
        vo.setReplayApplied(true);
        vo.setDuplicate(false);
        vo.setProcessStatusBefore(CONSUMED_REPAIR_REQUIRED);
        vo.setProcessStatusAfter(0);
        vo.setConsumerRetryCountBefore(target.getConsumerRetryCount());
        vo.setConsumerRetryCountAfter(target.getConsumerRetryCount());
        vo.setOutboxStatusNameBefore("SENT");
        vo.setOutboxStatusNameAfter("PENDING");
        vo.setConsumedAgeSeconds(evaluation.consumedAgeSeconds());
        vo.setReplayAttemptNo(replayAttemptNo);
        vo.setProcessedTtlSecondsAtReplay(evaluation.processedTtlSeconds());
        vo.setDeliveryMode(DELIVERY_MODE_OUTBOX_RELAY);
        vo.setCurrentProcessStatusName("RECEIVED");
        vo.setCurrentConsumerRetryCount(target.getConsumerRetryCount());
        vo.setCurrentOutboxStatusName("PENDING");
        vo.setMessage("已把同一 eventId 的消费账本重置为 RECEIVED、Outbox 退回 PENDING，并在同一事务写入"
                + " COMPLETED 审计。消息由既有 Outbox relay 领取投递（不在本事务内发送）："
                + "processed 证据仍在，重复投递会被判 DUPLICATE，不会重复累计；"
                + "随后由批量落库把事件推进到 FLUSHED。若 relay 长时间未投递，消息仍以 PENDING 留在"
                + " Outbox 表，可继续被领取；本接口不改 payload、不新建 eventId、不清零历史预算、"
                + "不刷新事件年龄，也不代表 T10 完成（DLQ/批量/自动修复仍待后续）。");
        return vo;
    }

    // ------------------------------------------------------------------ evaluation

    /**
     * 唯一的判定入口：预检查（不加锁）与重放（行锁）都调用它，保证读写拒绝条件一致。
     * 所有分支都不写任何状态。
     */
    private Evaluation evaluate(ConsumedTarget target, OutboxSource outbox, int replayAttempts) {
        int maxAttempts = properties.getStatus3ReplayMaxAttempts();
        long maxAge = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        boolean redisEvidence = target.getRedisAppliedAt() != null;
        Long age = target.getConsumedAgeSeconds();
        boolean withinSafeWindow = age != null && age >= 0 && age < maxAge;

        int processStatus = target.getProcessStatus() == null ? -1 : target.getProcessStatus();
        if (processStatus != CONSUMED_REPAIR_REQUIRED) {
            return refuse(REFUSAL_TARGET_NOT_REPAIR_REQUIRED, target, outbox, null, replayAttempts);
        }
        // 刷库型 status3（Redis 已应用、DB 未落库）：重投只会 DUPLICATE，修不了落库。
        if (redisEvidence) {
            return refuse(REFUSAL_FLUSH_ORIGIN_NOT_REPLAYABLE, target, outbox, null, replayAttempts);
        }
        if (!withinSafeWindow) {
            return refuse(REFUSAL_OVER_SAFE_WINDOW, target, outbox, null, replayAttempts);
        }
        // 投递源必须是仍存在且仍为 SENT 的 Outbox 行：否则没有可证明的持久化重投路径。
        if (outbox == null) {
            return refuse(REFUSAL_OUTBOX_LEDGER_MISSING, target, outbox, null, replayAttempts);
        }
        if (!Integer.valueOf(OUTBOX_STATUS_SENT).equals(outbox.getStatus())) {
            return refuse(REFUSAL_OUTBOX_NOT_SENT, target, outbox, null, replayAttempts);
        }

        VideoStatusChangedEvent event;
        try {
            event = objectMapper.readValue(outbox.getPayload(), VideoStatusChangedEvent.class);
        } catch (Exception e) {
            return refuse(REFUSAL_PAYLOAD_INVALID, target, outbox, null, replayAttempts);
        }
        try {
            event.validate();
        } catch (RuntimeException e) {
            // 不回传底层校验信息：可能带 payload 片段。
            return refuse(REFUSAL_EVENT_INVALID, target, outbox, null, replayAttempts);
        }
        if (!eventMatches(target, outbox, event)) {
            return refuse(REFUSAL_EVENT_MISMATCH, target, outbox, null, replayAttempts);
        }

        // 证明投递内容与消费账本是同一个事件：消费端 register 的 duplicate 分支正是用
        // payload_hash 做这个比对，不一致会被判 NonRetryable 并进 DLQ。
        String actualHash;
        try {
            actualHash = fingerprintService.hash(event);
        } catch (RuntimeException e) {
            return refuse(REFUSAL_FINGERPRINT_MISMATCH, target, outbox, null, replayAttempts);
        }
        if (!Objects.equals(actualHash, target.getPayloadHash())) {
            return refuse(REFUSAL_FINGERPRINT_MISMATCH, target, outbox, null, replayAttempts);
        }

        // occurredAt 由生产端 JVM 生成：未来超过容忍度或已超出安全窗口都拒绝。
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime occurredAt = event.occurredAt();
        if (occurredAt.isAfter(now.plusSeconds(EVENT_FUTURE_TOLERANCE_SECONDS))
                || !occurredAt.isAfter(now.minusSeconds(maxAge))) {
            return refuse(REFUSAL_OVER_SAFE_WINDOW, target, outbox, null, replayAttempts);
        }
        // T14-S2 固定政策上界（30 天）：maxAge 只要求小于 redisEventTtlDays，可以被调到 30 天
        // 以上；默认 7 天更严，所以这里只兜底“窗口被调大”的场景（默认配置下的超龄事件仍返回
        // 上面的原有码）。与 precheck 共用本方法，读写两侧因此给同一个码。
        if (VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(occurredAt, now)) {
            return refuse(VideoStatusEventAgeGuard.REFUSAL_CODE, target, outbox, null,
                    replayAttempts);
        }

        if (replayAttempts >= maxAttempts) {
            return refuse(REFUSAL_REPLAY_BUDGET_EXHAUSTED, target, outbox, null, replayAttempts);
        }

        Long processedTtlSeconds;
        try {
            processedTtlSeconds = stringRedisTemplate.getExpire(
                    RedisKeyUtils.processedKey(target.getEventId()), TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            // Redis 读失败不等于“没应用”，绝不放行。
            return refuse(REFUSAL_EVIDENCE_CHECK_FAILED, target, outbox, null, replayAttempts);
        }
        if (processedTtlSeconds == null || processedTtlSeconds == -1L) {
            return refuse(REFUSAL_PROCESSED_TTL_UNKNOWN, target, outbox,
                    processedTtlSeconds, replayAttempts);
        }
        if (processedTtlSeconds == -2L) {
            // 缺失不等于未应用：可能从未应用，也可能 processed 单独丢失。proven-only 一律拒绝。
            return refuse(REFUSAL_PROCESSED_EVIDENCE_MISSING, target, outbox,
                    processedTtlSeconds, replayAttempts);
        }
        if (processedTtlSeconds < properties.getStatus3ReplayRequiredProcessedTtlSeconds()) {
            return refuse(REFUSAL_PROCESSED_TTL_INSUFFICIENT, target, outbox,
                    processedTtlSeconds, replayAttempts);
        }

        return new Evaluation(DECISION_PROVEN_REPLAY_ELIGIBLE, true, null, redisEvidence,
                withinSafeWindow, age, outbox.getStatus(), processedTtlSeconds,
                replayAttempts, maxAttempts, event);
    }

    private static boolean eventMatches(ConsumedTarget target, OutboxSource outbox,
                                        VideoStatusChangedEvent event) {
        return target.getEventId().equals(event.eventId())
                && Objects.equals(target.getVid(), event.vid())
                && Objects.equals(outbox.getVid(), event.vid())
                && event.type() != null
                && Objects.equals(target.getEventType(), event.type().name())
                && Objects.equals(outbox.getEventType(), event.type().name())
                && Objects.equals(target.getDelta(), event.delta());
    }

    private Evaluation refuse(String refusalCode, ConsumedTarget target, OutboxSource outbox,
                             Long processedTtlSeconds, int replayAttempts) {
        long maxAge = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        Long age = target.getConsumedAgeSeconds();
        boolean withinSafeWindow = age != null && age >= 0 && age < maxAge;
        return new Evaluation(DECISION_REFUSED, false, refusalCode,
                target.getRedisAppliedAt() != null, withinSafeWindow, age,
                outbox == null ? null : outbox.getStatus(), processedTtlSeconds, replayAttempts,
                properties.getStatus3ReplayMaxAttempts(), null);
    }

    private static BusinessException rejection(Evaluation evaluation) {
        int httpCode = switch (evaluation.refusalCode()) {
            case REFUSAL_PAYLOAD_INVALID, REFUSAL_EVENT_INVALID, REFUSAL_EVENT_MISMATCH,
                 REFUSAL_FINGERPRINT_MISMATCH -> 422;
            default -> 409;
        };
        return new BusinessException(httpCode,
                refusalMessage(evaluation.refusalCode()) + "（reasonCode="
                        + evaluation.refusalCode() + "）；本次未修改 process_status/Outbox/"
                        + "计数/Redis，也未写审计");
    }

    private static String refusalMessage(String refusalCode) {
        return switch (refusalCode) {
            case REFUSAL_TARGET_NOT_REPAIR_REQUIRED ->
                    "目标不是 consumed status3（REPAIR_REQUIRED），本接口只重放 status3";
            case REFUSAL_FLUSH_ORIGIN_NOT_REPLAYABLE ->
                    "该 status3 带 redis_applied_at（Redis 已应用、DB 未落库的刷库型失败），"
                            + "重投只会被判 DUPLICATE 而无法补落库；请走刷库重试或门闩内重建（T12）";
            case REFUSAL_OVER_SAFE_WINDOW ->
                    "事件已超出安全重放窗口（DB 年龄或 occurredAt 超出），processed 幂等证据"
                            + "可能已过期，拒绝重放";
            case VideoStatusEventAgeGuard.REFUSAL_CODE ->
                    VideoStatusEventAgeGuard.REFUSAL_MESSAGE;
            case REFUSAL_OUTBOX_LEDGER_MISSING ->
                    "同一 eventId 的 Outbox 行不存在，无法证明存在持久化重投路径，"
                            + "不允许退化为请求线程直接发送";
            case REFUSAL_OUTBOX_NOT_SENT ->
                    "同一 eventId 的 Outbox 行不是 SENT（可能仍在投递/重试或已 FAILED），"
                            + "请先用 outbox 接口处置该行";
            case REFUSAL_PAYLOAD_INVALID -> "Outbox payload 无法解析为事件，不能安全重放";
            case REFUSAL_EVENT_INVALID -> "Outbox payload 不是合法事件，不能安全重放";
            case REFUSAL_EVENT_MISMATCH ->
                    "payload 的 eventId/vid/type/delta 与消费账本不一致，不能安全重放";
            case REFUSAL_FINGERPRINT_MISMATCH ->
                    "payload 的语义指纹与消费账本 payload_hash 不一致（或无法计算），"
                            + "重投会被消费端判为不可重试，拒绝重放";
            case REFUSAL_PROCESSED_EVIDENCE_MISSING ->
                    "processed 幂等证据缺失：它既可能表示从未应用，也可能表示 current 已应用而 "
                            + "processed 单独丢失，不能当作“未应用”重放";
            case REFUSAL_PROCESSED_TTL_UNKNOWN ->
                    "processed Key 存在但没有过期时间或无法判断剩余 TTL，不能证明重试窗口内仍可去重";
            case REFUSAL_PROCESSED_TTL_INSUFFICIENT ->
                    "processed Key 剩余 TTL 不足以覆盖 MQ 最大重试退避与投递租约，"
                            + "重试期间可能先过期导致重复累计";
            case REFUSAL_EVIDENCE_CHECK_FAILED ->
                    "读取 processed 证据失败（Redis 运行时异常），不能证明可安全去重";
            case REFUSAL_REPLAY_BUDGET_EXHAUSTED ->
                    "该 eventId 的人工重放次数已达上限，需要先定位根因（刷库异常/重建）";
            default -> "重放前置条件不满足";
        };
    }

    // ------------------------------------------------------------------ audit helpers

    private String auditMetadataJson(ConsumedTarget target, Evaluation evaluation,
                                     int replayAttemptNo) {
        ReplayAuditMetadata metadata = new ReplayAuditMetadata(
                AUDIT_METADATA_SCHEMA,
                CONSUMED_REPAIR_REQUIRED,
                0,
                target.getConsumerRetryCount(),
                target.getConsumerRetryCount(),
                evaluation.consumedAgeSeconds(),
                replayAttemptNo,
                OUTBOX_STATUS_SENT,
                0,
                evaluation.processedTtlSeconds());
        try {
            String json = objectMapper.writeValueAsString(metadata);
            if (json.length() > 1000) {
                throw new BusinessException(500,
                        "重放审计元数据超过 last_error 列长度，本次重放整体回滚");
            }
            return json;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500,
                    "重放审计元数据序列化失败，本次重放整体回滚（不允许无 before/after 留痕的重放）");
        }
    }

    /**
     * 重复提交：只认本动作、同 eventId、同 operator、同 reason 且元数据完整的 COMPLETED 审计行。
     * before/after 从持久化元数据原样重放，绝不用当前状态覆盖历史记录。
     */
    private VideoStatusStatus3ReplayVO duplicateResult(VideoStatusConsumptionRepairLog existing,
                                                       ConsumedTarget target, String eventId,
                                                       String reason, String operator) {
        boolean sameShape = Integer.valueOf(REPAIR_LOG_COMPLETED).equals(existing.getOperationStatus())
                && ACTION_REPLAY_TO_RECEIVED.equals(existing.getAction())
                && eventId.equals(existing.getEventId())
                && Objects.equals(operator, existing.getOperator());
        if (!sameShape) {
            throw new BusinessException(409,
                    "operationId 已被其他处置请求使用（目标/动作/操作人不一致），拒绝复用");
        }
        if (!Objects.equals(reason, existing.getReason())) {
            throw new BusinessException(409,
                    "operationId 已完成，但本次 reason 与已记录原因不一致，拒绝伪装成原结果");
        }
        ReplayAuditMetadata metadata = parseAuditMetadata(existing.getLastError());
        if (metadata == null) {
            throw new BusinessException(409,
                    "operationId 已存在，但审计元数据无法证明是本接口写入的 status3 重放，拒绝按重复提交返回");
        }

        VideoStatusStatus3ReplayVO vo = new VideoStatusStatus3ReplayVO();
        vo.setOperationId(existing.getOperationId());
        vo.setAction(existing.getAction());
        vo.setSource(VideoStatusFailureAdminService.SOURCE_CONSUMED);
        vo.setEventId(existing.getEventId());
        vo.setVid(target.getVid());
        vo.setEventType(target.getEventType());
        vo.setOperator(existing.getOperator());
        vo.setOperationStatus("COMPLETED");
        vo.setCompletedAt(existing.getCompletedAt());
        vo.setReplayApplied(false);
        vo.setDuplicate(true);
        vo.setProcessStatusBefore(metadata.processStatusBefore());
        vo.setProcessStatusAfter(metadata.processStatusAfter());
        vo.setConsumerRetryCountBefore(metadata.consumerRetryCountBefore());
        vo.setConsumerRetryCountAfter(metadata.consumerRetryCountAfter());
        vo.setOutboxStatusNameBefore(outboxStatusName(metadata.outboxStatusBefore()));
        vo.setOutboxStatusNameAfter(outboxStatusName(metadata.outboxStatusAfter()));
        vo.setConsumedAgeSeconds(metadata.consumedAgeSeconds());
        vo.setReplayAttemptNo(metadata.replayAttemptNo());
        vo.setProcessedTtlSecondsAtReplay(metadata.processedTtlSeconds());
        vo.setDeliveryMode(DELIVERY_MODE_OUTBOX_RELAY);
        vo.setCurrentProcessStatusName(consumedStatusName(target.getProcessStatus()));
        vo.setCurrentConsumerRetryCount(target.getConsumerRetryCount());
        OutboxSource currentOutbox = mapper.selectOutboxReplaySource(eventId);
        vo.setCurrentOutboxStatusName(currentOutbox == null
                ? null
                : outboxStatusName(currentOutbox.getStatus()));
        vo.setMessage("operationId 已完成过，本次未重复执行动作；before/after 从审计 last_error 元数据"
                + "原样重放，current* 字段是查询时的实时消费账本状态（两者不会互相覆盖）。");
        return vo;
    }

    private ReplayAuditMetadata parseAuditMetadata(String lastError) {
        if (lastError == null || !lastError.contains(AUDIT_METADATA_SCHEMA)) {
            return null;
        }
        try {
            ReplayAuditMetadata metadata =
                    objectMapper.readValue(lastError, ReplayAuditMetadata.class);
            if (!AUDIT_METADATA_SCHEMA.equals(metadata.schema())
                    || metadata.processStatusBefore() == null
                    || metadata.processStatusAfter() == null
                    || metadata.consumerRetryCountBefore() == null
                    || metadata.consumerRetryCountAfter() == null
                    || metadata.replayAttemptNo() == null
                    || metadata.outboxStatusBefore() == null
                    || metadata.outboxStatusAfter() == null
                    || metadata.processStatusBefore() != CONSUMED_REPAIR_REQUIRED
                    || metadata.processStatusAfter() != 0
                    || metadata.outboxStatusBefore() != OUTBOX_STATUS_SENT
                    || metadata.outboxStatusAfter() != 0) {
                return null;
            }
            return metadata;
        } catch (Exception e) {
            return null;
        }
    }

    private int countReplayAttempts(String eventId) {
        // cap = maxAttempts + 1 只为判断“是否达到上限”，不读全表历史。
        int cap = properties.getStatus3ReplayMaxAttempts() + 1;
        return mapper.countReplayAttempts(eventId, ACTION_REPLAY_TO_RECEIVED, cap);
    }

    private static String outboxStatusName(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 0 -> "PENDING";
            case 1 -> "SENDING";
            case 2 -> "SENT";
            case 3 -> "FAILED";
            default -> "UNKNOWN(" + status + ")";
        };
    }

    private static String consumedStatusName(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case 0 -> "RECEIVED";
            case 1 -> "REDIS_APPLIED_PENDING_FLUSH";
            case 2 -> "FLUSHED";
            case 3 -> "REPAIR_REQUIRED";
            default -> "UNKNOWN(" + status + ")";
        };
    }

    private static void requireConsumedSource(String source) {
        if (source == null || source.isBlank()) {
            throw new BusinessException(400, "source 必须为 CONSUMED（本接口只处理消费账本 status3）");
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        if (!VideoStatusFailureAdminService.SOURCE_CONSUMED.equals(normalized)) {
            throw new BusinessException(400,
                    "source 必须为 CONSUMED；Outbox FAILED 的单事件重放请用 "
                            + "POST /api/admin/video-status/outbox/{eventId}/replay");
        }
    }

    private static void requireEventId(String eventId) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 64) {
            throw new BusinessException(400, "eventId 不合法（必填，≤64）");
        }
    }

    private static void requireAuditFields(String operationId, String reason, String operator) {
        if (operationId == null || operationId.isBlank() || operationId.length() > 64) {
            throw new BusinessException(400, "operationId 不合法（必填，≤64）");
        }
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            throw new BusinessException(400, "reason 不合法（必填，≤1000）");
        }
        if (operator == null || operator.isBlank() || operator.length() > 64) {
            throw new BusinessException(401, "操作人只能来自登录态 currentUserId，缺失时拒绝执行");
        }
    }

    /**
     * 判定结果：预检查与重放共用。{@code event} 只在内部用于证明身份，绝不进入 HTTP 响应。
     */
    private record Evaluation(String decision, boolean eligible, String refusalCode,
                              boolean redisAppliedEvidencePresent, boolean withinSafeWindow,
                              Long consumedAgeSeconds, Integer outboxStatus,
                              Long processedTtlSeconds, int replayAttempts, int maxReplayAttempts,
                              VideoStatusChangedEvent event) {
    }

    /** 持久化在 {@code repair_log.last_error} 的操作元数据：只有状态/预算/年龄，无 payload。 */
    record ReplayAuditMetadata(String schema,
                               Integer processStatusBefore,
                               Integer processStatusAfter,
                               Integer consumerRetryCountBefore,
                               Integer consumerRetryCountAfter,
                               Long consumedAgeSeconds,
                               Integer replayAttemptNo,
                               Integer outboxStatusBefore,
                               Integer outboxStatusAfter,
                               Long processedTtlSeconds) {
    }
}
