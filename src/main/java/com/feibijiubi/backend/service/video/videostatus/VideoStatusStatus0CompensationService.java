package com.feibijiubi.backend.service.video.videostatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * T09 第一阶段：status0（RECEIVED）安全窗口原 eventId 补偿。
 *
 * <p>本阶段默认只实现<b>可证明安全</b>的子集：仅当 Redis processed Key 存在且剩余 TTL
 * 足以覆盖 MQ 最大重试退避与租约/confirm 余量时，才重新投递原事件。processed 缺失
 * 既可能表示“从未应用”，也可能表示“current 已应用但 processed 单独丢失”，不能推断为
 * 未应用；默认一律不自动重放、不转 status3，只记录冷却与诊断，交 T10/T12。</p>
 *
 * <p>只有同时满足 {@code status0-compensation-enabled}、
 * {@code status0-compensation-integrity-confirmed} 与
 * {@code status0-compensation-allow-unproven-replay} 三个闸门时，才允许操作员在
 * “已确认无 Redis 数据丢失/无部分写事故”的受控恢复模式下重放缺失证据的原 eventId。
 * 该模式不检测“单丢 processed”，事故时必须关闭总开关。</p>
 */
public interface VideoStatusStatus0CompensationService {

    /** preview 不访问 Redis，DB 行本身无法证明 current 是否已应用。 */
    String EVIDENCE_UNKNOWN = "EVIDENCE_UNKNOWN";
    String GATE_MASTER_SWITCH_OFF = "PREVIEW_ONLY_MASTER_SWITCH_OFF";
    String GATE_INTEGRITY_NOT_CONFIRMED = "BLOCKED_INTEGRITY_NOT_CONFIRMED";
    String GATE_PROVEN_PROCESSED_ONLY = "PROVEN_PROCESSED_EVIDENCE_ONLY";
    String GATE_ALLOW_UNPROVEN_REPLAY = "ALLOW_UNPROVEN_REPLAY";

    /** 只读诊断，不领取、不发送、不改任何状态，也是默认关闭时的唯一可用路径。 */
    PreviewResult preview(int limit, Long afterId);

    /**
     * 有界单轮：先短事务领取，再在事务外做证据校验与 MQ confirm。
     * 发送后崩溃时租约到期会再次领取同一 eventId。
     */
    RoundResult runOnce(Long afterId);

    record PreviewItem(
            String eventId,
            Integer vid,
            String eventType,
            int processStatus,
            Long consumedAgeSeconds,
            int compensationAttempts,
            LocalDateTime lastAttemptAt,
            LocalDateTime nextAttemptAt,
            LocalDateTime leaseUntil,
            boolean withinGrace,
            /** 与 runOnce 的年龄判定同源（DB 年龄 + occurredAt 窗口），不是只看 DB 年龄。 */
            boolean withinSafeWindow,
            boolean leaseFree,
            boolean cooldownElapsed,
            boolean attemptsRemaining,
            boolean eligible,
            String evidenceStatus,
            String decisionGate,
            /**
             * 与 runOnce 相同的拒绝原因码；{@code null} 表示年龄/内容判定放行。
             *
             * <p>包含 {@code EVENT_OVER_NORMAL_REPLAY_WINDOW}（T14-S2 固定 30 天上界）、
             * {@code STATUS0_OVER_SAFE_WINDOW}（可配置安全窗口）、
             * {@code STATUS0_PAYLOAD_INVALID} / {@code STATUS0_EVENT_INVALID} /
             * {@code STATUS0_EVENT_MISMATCH}。它<b>不</b>包含 Redis processed 证据类原因：
             * preview 不访问 Redis。</p>
             */
            String refusalCode
    ) {
    }

    record PreviewResult(
            long overSafeWindow,
            boolean overSafeWindowLowerBound,
            boolean compensationEnabled,
            List<PreviewItem> items
    ) {
    }

    record RoundResult(
            int claimed,
            int republished,
            int unprovenRepublished,
            int skippedNoProcessedEvidence,
            int releasedOnFailure,
            long overSafeWindow,
            boolean overSafeWindowLowerBound,
            Long nextCursor
    ) {
    }
}
