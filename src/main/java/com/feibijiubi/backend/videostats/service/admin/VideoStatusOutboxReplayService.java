package com.feibijiubi.backend.videostats.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.entity.VideoStatusConsumptionRepairLog;
import com.feibijiubi.backend.videostats.entity.VideoStatusOutbox;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.videostats.mapper.VideoStatusOutboxRepairMapper;
import com.feibijiubi.backend.videostats.service.VideoStatusEventAgeGuard;
import com.feibijiubi.backend.videostats.vo.VideoStatusOutboxReplayVO;
import com.feibijiubi.backend.videostats.vo.VideoStatusOutboxStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * T07 临时运维工具：<b>受审计的 Outbox FAILED 单事件重放</b>。
 *
 * <h2>这是 T10 的一个极窄子集，不是 T10</h2>
 * <p>本类只做一件事：把一个 <b>status=3 且可证明安全</b> 的 Outbox 事件在原事务里重置为
 * PENDING，并写一条 COMPLETED 审计。以下 T10 内容<b>明确未实现</b>：</p>
 * <ul>
 *   <li>DLQ 的分页查询/重放、批量处置；</li>
 *   <li>{@code video_status_consumed_event.process_status = 3}（REPAIR_REQUIRED）的修复；</li>
 *   <li>对账（业务真值 / 事件账本 / DB 基线 / Redis 派生值）与人工确认忽略；</li>
 *   <li>status 0/1 的人工干预（仍在自动投递/租约恢复职责内，暂不提供）；</li>
 *   <li><b>任何</b> consumed 记录的处置：只要消费账本已存在一行，本工具就拒绝，不改统计、
 *       不改 consumed、不改 current/Redis。这不代表“已存在 consumed 时重放一定安全”，
 *       而是把不确定的 processed 丢失边界交回 T09/T10。</li>
 * </ul>
 *
 * <h2>安全不变量</h2>
 * <ol>
 *   <li><b>幂等</b>：{@code operationId} 唯一。只有（eventId, action, operator, reason）全都相同
 *       的已完成请求才返回原结果；reason 变了同样 409，绝不能用新原因复用旧操作、
 *       伪装成原结果。</li>
 *   <li><b>不新建 eventId</b>：保留原 {@code event_id} 与 {@code payload}，只是让 relay 重新投递同一条事件，
 *       重复投递由消费端 eventId 幂等吸收。</li>
 *   <li><b>可证明才重放</b>：payload 必须可解析、{@code event.validate()} 通过，
 *       且 eventId/vid/type 与数据库行一致；否则拒绝（属于 T10 修复场景）。</li>
 *   <li><b>安全窗口（双重 + 固定上界）</b>：{@code created_at}（DB 时钟，{@code TIMESTAMPDIFF} 秒）与
 *       payload 的 {@code occurredAt}（生产端 JVM 时钟）都必须落在
 *       {@code consumerRecoveryAutoReplayMaxAgeSeconds} 内；<b>正好等于上限也拒绝</b>。
 *       {@code occurredAt} 在未来（超过 now+5min）同样拒绝，防止伪造新鲜度绕过窗口。
 *       此外 {@code occurredAt} 还受 T14-S2 的固定 30 天上界约束：该可调窗口只要求小于
 *       {@code redisEventTtlDays}，因此可以被调到 30 天以上；超过 30 天的普通重放一律拒绝
 *       （{@code EVENT_OVER_NORMAL_REPLAY_WINDOW}），只能转人工核查。</li>
 *   <li><b>不动账本</b>：不写 video_status、不改 consumed_event、不碰 Redis。
 *       consumed 已存在（0/1/2/3 任一）都明确拒绝，不声称“安全”也不自动处置。</li>
 *   <li><b>同事务 + CAS</b>：状态重置（{@code status=3} 的 CAS，影响行数必须为 1）、
 *       审计插入（影响行数必须为 1）、以及审计里的 before/after 操作元数据都在同一 MySQL 事务内，
 *       任一失败/并发冲突整体回滚。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class VideoStatusOutboxReplayService {

    /** 审计动作名，写入 {@code video_status_consumption_repair_log.action}。 */
    public static final String ACTION_REPLAY_TO_PENDING = "REPLAY_OUTBOX_TO_PENDING";

    /** 写入 {@code repair_log.last_error} 的操作元数据版本，用于 duplicate 重放原始 before/after。 */
    static final String AUDIT_METADATA_SCHEMA = "T07_REPLAY_META_V2";

    /** 旧版审计元数据（缺 confirmWriteback 字段）仍可读，但不补造未知值。 */
    static final String AUDIT_METADATA_SCHEMA_V1 = "T07_REPLAY_META_V1";

    /** 允许生产端时钟轻微超前；超过即视为“未来事件”，拒绝。 */
    private static final long EVENT_FUTURE_TOLERANCE_SECONDS = 300L;

    /** GET 只给固定提示，绝不把历史 last_error 原文（可能含 payload/凭证）返回给 HTTP。 */
    public static final String LAST_ERROR_HINT =
            "存在失败详情，但 HTTP 不返回 last_error 原文（历史值可能含 payload/凭证片段）；请受控查库诊断";

    private static final int OUTBOX_STATUS_PENDING = 0;
    private static final int OUTBOX_STATUS_SENDING = 1;
    private static final int OUTBOX_STATUS_SENT = 2;
    private static final int OUTBOX_STATUS_FAILED = 3;
    private static final int REPAIR_LOG_COMPLETED = 1;

    private final VideoStatusOutboxRepairMapper repairMapper;
    private final VideoStatusProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 管理员只读查询：安全状态 / 计数 / 时间 / “是否存在失败详情”，不含 payload、lease_token
     * 与 last_error 原文。
     */
    @Transactional(readOnly = true)
    public VideoStatusOutboxStatusVO getStatus(String eventId) {
        requireEventId(eventId);
        VideoStatusOutbox row = repairMapper.selectSafeByEventId(eventId);
        if (row == null) {
            throw new BusinessException(404, "没有找到该 eventId 的 Outbox 事件");
        }
        Integer consumed = repairMapper.selectConsumedProcessStatus(eventId);
        boolean lastErrorPresent = Boolean.TRUE.equals(repairMapper.selectLastErrorPresent(eventId));

        VideoStatusOutboxStatusVO vo = new VideoStatusOutboxStatusVO();
        vo.setEventId(row.getEventId());
        vo.setVid(row.getAggregateId());
        vo.setEventType(row.getEventType());
        vo.setStatus(row.getStatus());
        vo.setStatusName(statusName(row.getStatus()));
        vo.setRetryCount(row.getRetryCount());
        vo.setLeaseRecoveryCount(row.getLeaseRecoveryCount());
        vo.setConfirmWritebackCount(row.getConfirmWritebackCount());
        vo.setCreatedAt(row.getCreatedAt());
        vo.setNextRetryAt(row.getNextRetryAt());
        vo.setSendingAt(row.getSendingAt());
        vo.setSentAt(row.getSentAt());
        vo.setLastErrorPresent(lastErrorPresent);
        vo.setLastErrorHint(lastErrorPresent ? LAST_ERROR_HINT : null);
        vo.setConsumedProcessStatus(consumed);

        String blocked = staticBlockReason(row, consumed);
        vo.setReplayEligible(blocked == null);
        vo.setReplayBlockedReason(blocked);
        vo.setNote("payload/leaseToken/last_error 原文均不返回；replayEligible 只是必要条件，"
                + "POST 重放会再次校验 payload、eventId/vid/type、createdAt 与 occurredAt 双重安全窗口、"
                + "occurredAt 的固定 " + VideoStatusEventAgeGuard.MAX_NORMAL_REPLAY_AGE_DAYS
                + " 天普通重放上界（reasonCode=" + VideoStatusEventAgeGuard.REFUSAL_CODE + "）、"
                + "以及“consumed 一律拒绝”的账本边界。"
                + "本工具不覆盖 DLQ 与任何 consumed 处置（T09/T10 范围）。");
        return vo;
    }

    /**
     * 受审计重放：FAILED → PENDING，并写 COMPLETED 审计（含 before/after 元数据）。
     *
     * <p>所有拒绝都发生在写审计之前，拒绝请求不留下任何审计行。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public VideoStatusOutboxReplayVO replay(String eventId,
                                            String operationId,
                                            String reason,
                                            String operator) {
        requireEventId(eventId);
        requireAuditFields(operationId, reason, operator);

        // 1) 行锁：同一 eventId 的重放串行；锁内再读 operationId，避免重复动作。
        VideoStatusOutbox row = repairMapper.selectByEventIdForUpdate(eventId);
        if (row == null) {
            throw new BusinessException(404, "没有找到该 eventId 的 Outbox 事件");
        }

        VideoStatusConsumptionRepairLog existing =
                repairMapper.selectRepairLogByOperationId(operationId);
        if (existing != null) {
            requireSameOperation(existing, eventId, reason, operator);
            return duplicateResult(existing, row);
        }

        // 2) 状态机：只处理 FAILED，其它状态给出明确原因。
        int status = row.getStatus() == null ? -1 : row.getStatus();
        if (status == OUTBOX_STATUS_SENT) {
            throw new BusinessException(409,
                    "该事件已完成投递（status=2 SENT），无需重放；重复投递只会被消费端幂等丢弃");
        }
        if (status == OUTBOX_STATUS_PENDING || status == OUTBOX_STATUS_SENDING) {
            throw new BusinessException(409,
                    "该事件处于 PENDING/SENDING（status=" + status + "），仍由自动投递/租约恢复负责；"
                            + "人工重放不执行，如需处置请走 T09/T10 流程");
        }
        if (status != OUTBOX_STATUS_FAILED) {
            throw new BusinessException(409, "未知的 Outbox 状态（status=" + status + "），拒绝重放");
        }

        // 3) 安全窗口之一：created_at 用 DB 时钟（TIMESTAMPDIFF 秒）判断，
        //    避免 JVM 与 DB 时区（+8 / UTC）差异把边界算歪；正好等于上限也拒绝。
        long maxAgeSeconds = properties.getConsumerRecoveryAutoReplayMaxAgeSeconds();
        Long createdAgeSeconds = repairMapper.selectCreatedAgeSeconds(eventId);
        if (createdAgeSeconds == null) {
            throw new BusinessException(409,
                    "无法从数据库读取 created_at 年龄，不能证明在安全重放窗口内，拒绝重放");
        }
        if (createdAgeSeconds < 0) {
            // created_at 在未来（DB 时钟）：既不能证明“够新”，也不能证明窗口有意义，
            // 很可能是时钟漂移/手工改库/伪造新鲜度，直接拒绝而不是当成 0 秒放行。
            throw new BusinessException(409,
                    "事件的 created_at 在未来（DB 时钟年龄 " + createdAgeSeconds
                            + "s < 0），不能证明在安全重放窗口内，拒绝重放；请先核对数据库时钟与行数据");
        }
        if (createdAgeSeconds >= maxAgeSeconds) {
            throw new BusinessException(409,
                    "事件 created_at 已达/超过安全重放窗口（DB 时钟 " + createdAgeSeconds
                            + "s ≥ " + maxAgeSeconds + "s），拒绝重放；"
                            + "processed 幂等证据可能已过期，请走 T10 对账/业务补偿");
        }

        // 4) payload 必须能证明是同一个合法事件。
        VideoStatusChangedEvent event = parseAndMatch(row, eventId);

        // 5) 安全窗口之二：occurredAt 由生产端 JVM 生成，用 JVM 时钟比较；
        //    未来时间（> now+5min）拒绝，防止伪造成“刚发生”绕过窗口。
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime occurredAt = event.occurredAt();
        if (occurredAt.isAfter(now.plusSeconds(EVENT_FUTURE_TOLERANCE_SECONDS))) {
            throw new BusinessException(422,
                    "payload 的 occurredAt 在未来（超过 now+5min），拒绝重放；需要 T10 核对事件来源");
        }
        LocalDateTime occurredDeadline = now.minusSeconds(maxAgeSeconds);
        if (!occurredAt.isAfter(occurredDeadline)) {
            throw new BusinessException(409,
                    "payload 的 occurredAt 已达/超过安全重放窗口（" + maxAgeSeconds + "s），拒绝重放；"
                            + "请走 T10 对账/业务补偿，不重发旧事件");
        }

        // 5b) T14-S2 固定政策上界（30 天）：上面的可配置窗口只要求小于 redisEventTtlDays，
        //     因此可以被调到 30 天以上；默认 7 天更严，所以这里只兜底“窗口被调大”的场景，
        //     默认配置下的超龄事件仍然给出上面的原有原因，不会换成新码。
        //     本检查在状态 CAS / Outbox 重置 / 审计写入之前，拒绝路径不产生任何写。
        if (VideoStatusEventAgeGuard.isOutsideNormalReplayWindow(occurredAt, now)) {
            throw new BusinessException(409,
                    VideoStatusEventAgeGuard.REFUSAL_MESSAGE
                            + "（reasonCode=" + VideoStatusEventAgeGuard.REFUSAL_CODE + "）；"
                            + "本次未重置状态、未写审计、未重发消息");
        }

        // 6) 消费账本：临时工具从严——只要已存在 consumed 记录就拒绝，
        //    不假设 0/1/2 下重放一定安全，也不改 consumed/统计/current。
        Integer consumed = repairMapper.selectConsumedProcessStatus(eventId);
        if (consumed != null) {
            throw consumedRejection(consumed);
        }

        int retryBefore = row.getRetryCount() == null ? 0 : row.getRetryCount();
        int leaseBefore = row.getLeaseRecoveryCount() == null ? 0 : row.getLeaseRecoveryCount();
        int confirmWritebackBefore = row.getConfirmWritebackCount() == null
                ? 0
                : row.getConfirmWritebackCount();

        // 7) CAS：status=3 才允许回到 PENDING，影响行数必须恰好 1。
        int updated = repairMapper.resetFailedToPending(eventId);
        if (updated != 1) {
            throw new BusinessException(409,
                    "状态已被并发修改（CAS 影响行数=" + updated + "），本次重放整体回滚，请重新 GET 后决定");
        }

        // 8) 同一事务写 COMPLETED 审计，并把 before/after 操作元数据持久化到 last_error：
        //    duplicate 请求据此重放原始 before/after，而不是“只在首次 VO 里出现过”。
        VideoStatusConsumptionRepairLog log = new VideoStatusConsumptionRepairLog();
        log.setOperationId(operationId);
        log.setEventId(eventId);
        log.setAction(ACTION_REPLAY_TO_PENDING);
        log.setReason(reason);
        log.setOperator(operator);
        log.setLastError(auditMetadataJson(status, retryBefore, leaseBefore,
                confirmWritebackBefore, OUTBOX_STATUS_PENDING, 0, 0, 0));
        int inserted;
        try {
            inserted = repairMapper.insertCompletedRepairLog(log);
        } catch (DuplicateKeyException e) {
            // 唯一键冲突（operation_id / event_id 等）说明本次动作与已有审计行撞键：
            // 不能让“状态已重置但没有独立审计”半成品留下，转成 409 让事务整体回滚。
            throw new BusinessException(409,
                    "审计行唯一键冲突（operationId/eventId 已存在），本次重放整体回滚，"
                            + "不重复执行动作；请重新 GET 后换 new operationId 或走 duplicate 幂等查询");
        }
        if (inserted != 1) {
            throw new BusinessException(500,
                    "审计写入影响行数异常（" + inserted + "），本次重放整体回滚；请排查审计表/唯一键后重试");
        }

        VideoStatusOutboxReplayVO vo = new VideoStatusOutboxReplayVO();
        vo.setOperationId(operationId);
        vo.setAction(ACTION_REPLAY_TO_PENDING);
        vo.setEventId(eventId);
        vo.setVid(row.getAggregateId());
        vo.setEventType(row.getEventType());
        vo.setOperator(operator);
        vo.setOperationStatus("COMPLETED");
        vo.setCompletedAt(LocalDateTime.now());
        vo.setReplayApplied(true);
        vo.setDuplicate(false);
        vo.setRetryCountBefore(retryBefore);
        vo.setRetryCountAfter(0);
        vo.setLeaseRecoveryCountBefore(leaseBefore);
        vo.setLeaseRecoveryCountAfter(0);
        vo.setConfirmWritebackCountBefore(confirmWritebackBefore);
        vo.setConfirmWritebackCountAfter(0);
        vo.setStatusNameBefore(statusName(OUTBOX_STATUS_FAILED));
        vo.setStatusNameAfter(statusName(OUTBOX_STATUS_PENDING));
        vo.setCurrentStatusName(statusName(OUTBOX_STATUS_PENDING));
        vo.setCurrentRetryCount(0);
        vo.setCurrentLeaseRecoveryCount(0);
        vo.setCurrentConfirmWritebackCount(0);
        vo.setMessage("已把原事件（同一 eventId、原 payload、原 vid/type）重置为 PENDING，"
                + "并把 status/retry/lease/confirm-writeback before-after 写入 COMPLETED 审计的 last_error 元数据；"
                + "三份预算归零是人工重放开启的新预算，outbox.last_error 保留作为失败证据。"
                + "本工具不代表 T10 完成：DLQ 与任何 consumed 记录仍需 T09/T10。");
        return vo;
    }

    // ------------------------------------------------------------------ helpers

    /** 幂等判定：eventId/action/operator/reason 全部一致才算“同一个已完成操作”。 */
    private void requireSameOperation(VideoStatusConsumptionRepairLog existing,
                                      String eventId,
                                      String reason,
                                      String operator) {
        boolean sameShape = Integer.valueOf(REPAIR_LOG_COMPLETED).equals(existing.getOperationStatus())
                && ACTION_REPLAY_TO_PENDING.equals(existing.getAction())
                && eventId.equals(existing.getEventId())
                && operator.equals(existing.getOperator());
        if (!sameShape) {
            throw new BusinessException(409,
                    "operationId 已被其他处置请求使用（eventId/action/operator 不一致），拒绝执行以免重复动作");
        }
        if (!Objects.equals(reason, existing.getReason())) {
            throw new BusinessException(409,
                    "operationId 已完成，但本次 reason 与已记录原因不一致（reason 冲突），拒绝复用旧操作；"
                            + "绝不把新原因伪装成原结果，请换 new operationId 或沿用原 reason");
        }
    }

    /**
     * 重复提交：返回原审计记录的历史操作（before/after 从持久化元数据重放），
     * 同时把当前 outbox 状态单独列出，绝不用当前状态覆盖原始记录。
     */
    private VideoStatusOutboxReplayVO duplicateResult(VideoStatusConsumptionRepairLog existing,
                                                      VideoStatusOutbox row) {
        VideoStatusOutboxReplayVO vo = new VideoStatusOutboxReplayVO();
        vo.setOperationId(existing.getOperationId());
        vo.setAction(existing.getAction());
        vo.setEventId(existing.getEventId());
        vo.setVid(row.getAggregateId());
        vo.setEventType(row.getEventType());
        vo.setOperator(existing.getOperator());
        vo.setOperationStatus("COMPLETED");
        vo.setCompletedAt(existing.getCompletedAt());
        vo.setReplayApplied(false);
        vo.setDuplicate(true);
        vo.setCurrentStatusName(statusName(row.getStatus()));
        vo.setCurrentRetryCount(row.getRetryCount());
        vo.setCurrentLeaseRecoveryCount(row.getLeaseRecoveryCount());
        vo.setCurrentConfirmWritebackCount(row.getConfirmWritebackCount());

        ReplayAuditMetadata metadata = parseAuditMetadata(existing.getLastError());
        if (metadata != null) {
            vo.setStatusNameBefore(statusName(metadata.statusBefore()));
            vo.setStatusNameAfter(statusName(metadata.statusAfter()));
            vo.setRetryCountBefore(metadata.retryBefore());
            vo.setRetryCountAfter(metadata.retryAfter());
            vo.setLeaseRecoveryCountBefore(metadata.leaseRecoveryBefore());
            vo.setLeaseRecoveryCountAfter(metadata.leaseRecoveryAfter());
            vo.setConfirmWritebackCountBefore(metadata.confirmWritebackBefore());
            vo.setConfirmWritebackCountAfter(metadata.confirmWritebackAfter());
            vo.setMessage("operationId 已完成过，本次未重复执行动作；before/after 从审计 last_error 元数据原样重放，"
                    + "current* 字段是查询时的实时状态（两者不会互相覆盖）");
        } else {
            vo.setMessage("operationId 已完成过，本次未重复执行动作；历史审计没有操作元数据（旧版本写入），"
                    + "无法重放原始 before/after，current* 字段是查询时的实时状态；原审计行未被修改");
        }
        return vo;
    }

    private String auditMetadataJson(int statusBefore,
                                     int retryBefore,
                                     int leaseBefore,
                                     int confirmWritebackBefore,
                                     int statusAfter,
                                     int retryAfter,
                                     int leaseAfter,
                                     int confirmWritebackAfter) {
        ReplayAuditMetadata metadata = new ReplayAuditMetadata(
                AUDIT_METADATA_SCHEMA,
                statusBefore, retryBefore, leaseBefore, confirmWritebackBefore,
                statusAfter, retryAfter, leaseAfter, confirmWritebackAfter);
        try {
            String json = objectMapper.writeValueAsString(metadata);
            if (json.length() > 1000) {
                throw new BusinessException(500,
                        "审计元数据序列化结果超过 last_error 列长度（VARCHAR(1000)），本次重放整体回滚");
            }
            return json;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(500,
                    "审计元数据序列化失败，本次重放整体回滚（不允许出现没有 before/after 留痕的重放）");
        }
    }

    /** 只接受本工具写入的元数据；旧版/其它来源的 last_error 一律当作“无元数据”，绝不猜测。 */
    private ReplayAuditMetadata parseAuditMetadata(String lastError) {
        if (lastError == null
                || (!lastError.contains(AUDIT_METADATA_SCHEMA)
                && !lastError.contains(AUDIT_METADATA_SCHEMA_V1))) {
            return null;
        }
        try {
            ReplayAuditMetadata metadata =
                    objectMapper.readValue(lastError, ReplayAuditMetadata.class);
            if (!AUDIT_METADATA_SCHEMA.equals(metadata.schema())
                    && !AUDIT_METADATA_SCHEMA_V1.equals(metadata.schema())) {
                return null;
            }
            if (!isValidMetadata(metadata)) {
                return null;
            }
            return metadata;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 审计元数据必须“完整且自洽”才允许当作原始 before/after 重放：
     * 状态是合法取值（0..3）、计数非负；V2 还要求 confirmWriteback 两项非 null。
     * V1 历史行允许 confirmWriteback 为 null（当时尚未引入该预算），绝不补造未知值。
     */
    private static boolean isValidMetadata(ReplayAuditMetadata metadata) {
        if (metadata.statusBefore() == null || metadata.statusAfter() == null
                || metadata.retryBefore() == null || metadata.retryAfter() == null
                || metadata.leaseRecoveryBefore() == null || metadata.leaseRecoveryAfter() == null) {
            return false;
        }
        if (AUDIT_METADATA_SCHEMA.equals(metadata.schema())
                && (metadata.confirmWritebackBefore() == null
                || metadata.confirmWritebackAfter() == null)) {
            return false;
        }
        if (!isKnownOutboxStatus(metadata.statusBefore())
                || !isKnownOutboxStatus(metadata.statusAfter())) {
            return false;
        }
        return metadata.retryBefore() >= 0 && metadata.retryAfter() >= 0
                && metadata.leaseRecoveryBefore() >= 0 && metadata.leaseRecoveryAfter() >= 0
                && (metadata.confirmWritebackBefore() == null
                || metadata.confirmWritebackBefore() >= 0)
                && (metadata.confirmWritebackAfter() == null
                || metadata.confirmWritebackAfter() >= 0);
    }

    private static boolean isKnownOutboxStatus(int status) {
        return status == OUTBOX_STATUS_PENDING
                || status == OUTBOX_STATUS_SENDING
                || status == OUTBOX_STATUS_SENT
                || status == OUTBOX_STATUS_FAILED;
    }

    private VideoStatusChangedEvent parseAndMatch(VideoStatusOutbox row, String eventId) {
        VideoStatusChangedEvent event;
        try {
            event = objectMapper.readValue(row.getPayload(), VideoStatusChangedEvent.class);
        } catch (Exception e) {
            throw new BusinessException(422,
                    "原 payload 无法解析（PAYLOAD_JSON_INVALID），不能安全重放；需要 T10 修复");
        }
        try {
            event.validate();
        } catch (RuntimeException e) {
            // 不回传 e.getMessage()：底层校验信息可能带上 payload 片段/字段值，
            // HTTP 只给稳定的错误码与固定说明，细节留在受控查库诊断。
            throw new BusinessException(422,
                    "原 payload 不是合法事件（EVENT_INVALID），不能安全重放；需要 T10 修复");
        }
        if (!eventId.equals(event.eventId())) {
            throw new BusinessException(422, "payload 的 eventId 与行不一致（EVENT_MISMATCH_EVENT_ID），拒绝重放");
        }
        if (!Objects.equals(row.getAggregateId(), event.vid())) {
            throw new BusinessException(422, "payload 的 vid 与行的 aggregate_id 不一致（EVENT_MISMATCH_VID），拒绝重放");
        }
        if (event.type() == null || !event.type().name().equals(row.getEventType())) {
            throw new BusinessException(422, "payload 的 type 与行的 event_type 不一致（EVENT_MISMATCH_TYPE），拒绝重放");
        }
        return event;
    }

    /** GET 的“是否可能可重放”：只做不需要解析 payload 的静态判断。 */
    private String staticBlockReason(VideoStatusOutbox row, Integer consumed) {
        int status = row.getStatus() == null ? -1 : row.getStatus();
        if (status == OUTBOX_STATUS_SENT) {
            return "status=2 SENT：已完成投递，无需重放";
        }
        if (status == OUTBOX_STATUS_PENDING || status == OUTBOX_STATUS_SENDING) {
            return "status=" + status + "：仍在自动投递/租约恢复职责内，人工重放不执行";
        }
        if (status != OUTBOX_STATUS_FAILED) {
            return "未知状态 status=" + status;
        }
        if (consumed != null) {
            return "消费账本已存在（process_status=" + consumed + "）：T07 临时工具任何 consumed 都拒绝重放，"
                    + "需 T09/T10 处置";
        }
        return null;
    }

    /** consumed 已存在 → 一律 409；分别说明 2 / 0,1 / 3 的归属，绝不声称安全。 */
    private static BusinessException consumedRejection(int consumed) {
        String detail = switch (consumed) {
            case 2 -> "process_status=2 FLUSHED 表示该事件已完整处理，无需重投";
            case 3 -> "process_status=3 REPAIR_REQUIRED 需要 T10 修复，不能通过重放绕过";
            default -> "process_status=" + consumed
                    + " 仍由自动消费/恢复负责（T09），人工重放不介入";
        };
        return new BusinessException(409,
                "消费账本已有记录（" + detail + "）。T07 临时工具从严：任何已存在的 consumed 都拒绝重放，"
                        + "以免在 processed 幂等证据不确定时造成丢失或重复计数；"
                        + "本工具不改统计、不改 consumed、不改 current/Redis");
    }

    private static String statusName(Integer status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case OUTBOX_STATUS_PENDING -> "PENDING";
            case OUTBOX_STATUS_SENDING -> "SENDING";
            case OUTBOX_STATUS_SENT -> "SENT";
            case OUTBOX_STATUS_FAILED -> "FAILED";
            default -> "UNKNOWN(" + status + ")";
        };
    }

    private static void requireEventId(String eventId) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 64) {
            throw new BusinessException(400, "eventId 不合法");
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
     * 持久化在 {@code repair_log.last_error} 的操作元数据（JSON）。只记录状态/预算的 before/after，
     * 不含 payload、租约 token 或任何凭证。
     */
    public record ReplayAuditMetadata(
            String schema,
            Integer statusBefore,
            Integer retryBefore,
            Integer leaseRecoveryBefore,
            Integer confirmWritebackBefore,
            Integer statusAfter,
            Integer retryAfter,
            Integer leaseRecoveryAfter,
            Integer confirmWritebackAfter
    ) {
        /** 兼容旧 V1 元数据（没有 confirmWriteback 两项），保留 null 表示未知。 */
        public ReplayAuditMetadata(String schema,
                                   Integer statusBefore,
                                   Integer retryBefore,
                                   Integer leaseRecoveryBefore,
                                   Integer statusAfter,
                                   Integer retryAfter,
                                   Integer leaseRecoveryAfter) {
            this(schema, statusBefore, retryBefore, leaseRecoveryBefore, null,
                    statusAfter, retryAfter, leaseRecoveryAfter, null);
        }
    }
}
