package com.feibijiubi.backend.videostats.service;

import java.time.LocalDateTime;

/**
 * T14-S1：普通事件重放的绝对年龄上限，全链路唯一的判定实现。
 *
 * <p>已批准政策：最大<b>普通</b>事件重放窗口是事件发生后 30 天，超过转人工核查、拒绝普通重放。
 * 本类只回答“这条事件是否已经超出该窗口”，不做任何读写：不查消费账本、不改状态、不写
 * Redis，因此判定结果<b>不依赖账本行是否存在</b>——这正是它必须与
 * {@code VideoStatusConsumptionServiceImpl#handleReceived} 里那套基于 {@code consumed_at}
 * 的重投守卫分开的原因。</p>
 *
 * <p>边界是显式的：<b>恰好 30 天仍然允许</b>（严格 {@code occurredAt.isBefore(now - 30 天)}
 * 才算超龄），并且比较不截断纳秒。未来时间不加新拒绝，保持既有行为；{@code occurredAt}
 * 由业务侧 {@code LocalDateTime.now()} 产生，没有防篡改能力，本判定不是防篡改机制。</p>
 *
 * <p>调用点（T14-S1 + T14-S2）：消费登记（{@code VideoStatusConsumptionServiceImpl#register}）、
 * T07 Outbox FAILED 单事件重放、T10-B1 consumed status3 重放的 precheck/replay、
 * T09 status0 补偿的 preview/runOnce。每个调用点都把它放在<b>任何状态重置、审计写入或
 * MQ 发布之前</b>，并且排在各自原有的“可配置安全窗口”判定<b>之后</b>——默认 7 天更严，
 * 因此默认配置下超龄事件仍然给出原来的原因码，本上界只在运维把窗口调到 30 天以上时兜底。</p>
 */
public final class VideoStatusEventAgeGuard {

    /**
     * 最大普通重放年龄（天）。固定常量：政策上限不放宽，因此不提供配置项，
     * 也不复用 {@code app.video-status.*} 的任何可调参数。
     */
    public static final int MAX_NORMAL_REPLAY_AGE_DAYS = 30;

    /**
     * 超龄拒绝的固定原因文本。不含 payload、eventId 或任何用户数据，可安全写入死信
     * 消息头与日志。
     */
    public static final String EXPIRED_REASON =
            "统计事件已超过普通重放最大年龄 " + MAX_NORMAL_REPLAY_AGE_DAYS + " 天，拒绝消费";

    /**
     * 超出固定 30 天上界时的统一原因码：T07 Outbox 重放、T10-B1 status3 重放的
     * precheck 与 replay、T09 status0 补偿的 preview 与 runOnce 共用同一个字符串，
     * 因此读侧看到的码就是写侧拒绝的码。
     *
     * <p>刻意<b>不</b>复用各入口已有的“超出安全窗口”码（{@code CONSUMED_OVER_SAFE_WINDOW} /
     * {@code STATUS0_OVER_SAFE_WINDOW}）：那两个码描述的是<b>可配置</b>安全窗口
     * （{@code consumer-recovery-auto-replay-max-age-seconds}，启动校验只要求小于
     * {@code redisEventTtlDays}，因此允许被调到 30 天以上），而这里是<b>固定政策上界</b>。
     * 混用会让运维无法区分“配置窗口拒绝”和“政策上界拒绝”（两者的处置完全不同：
     * 前者等窗口或修配置，后者只能转人工核查），而且原码的说明文本会变成不成立的说法。</p>
     */
    public static final String REFUSAL_CODE = "EVENT_OVER_NORMAL_REPLAY_WINDOW";

    /** 与 {@link #REFUSAL_CODE} 配对的固定说明文本，不含 payload/eventId，读写两侧共用。 */
    public static final String REFUSAL_MESSAGE =
            "事件发生时间已超过普通重放最大年龄 " + MAX_NORMAL_REPLAY_AGE_DAYS
                    + " 天（固定政策上界），只能转人工核查，拒绝重放";

    private VideoStatusEventAgeGuard() {
    }

    /**
     * 事件是否已经超出普通重放窗口。
     *
     * @param occurredAt 事件发生时间（业务侧无时区语义的 {@code LocalDateTime}），
     *                   调用方需保证非 null（{@code VideoStatusChangedEvent#validate} 已校验）
     * @param now        判定基准时间，必须与 {@code occurredAt} 同源（同一个 Clock）
     * @return {@code true} 表示严格早于 {@code now - 30 天}，应当拒绝；恰好 30 天返回 {@code false}
     */
    public static boolean isOutsideNormalReplayWindow(
            LocalDateTime occurredAt,
            LocalDateTime now
    ) {
        return occurredAt.isBefore(
                now.minusDays(MAX_NORMAL_REPLAY_AGE_DAYS)
        );
    }
}
