package com.feibijiubi.backend.videostats.mapper;

import com.feibijiubi.backend.videostats.entity.VideoStatusConsumptionRepairLog;
import com.feibijiubi.backend.videostats.entity.VideoStatusOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * T07 临时运维（T10 极窄子集）专用 mapper。
 *
 * <p>刻意<b>独立于</b> {@link VideoStatusOutboxMapper}：</p>
 * <ul>
 *   <li>不修改现有 relay 使用的语句/签名/XML，回滚本工具时对投递零影响；</li>
 *   <li>只有管理员修复需要的几类语句：安全只读查询（含 DB 时钟年龄）、行锁查询、单行 CAS、审计写入；</li>
 *   <li>没有“改 eventId / 改 payload / 改消费账本 / 改 video_status”的语句，
 *       避免工具被扩展成改账本的后门。</li>
 * </ul>
 */
@Mapper
public interface VideoStatusOutboxRepairMapper {

    /**
     * 管理员 GET 用：只取安全列（<b>不查 payload / lease_token / last_error 原文</b>），
     * 不加锁、不改状态。
     */
    VideoStatusOutbox selectSafeByEventId(@Param("eventId") String eventId);

    /**
     * GET 用：只回答“是否存在失败详情”，不把 last_error 原文（可能含 payload/凭证）带回应用层/HTTP。
     */
    Boolean selectLastErrorPresent(@Param("eventId") String eventId);

    /**
     * 重放用：按唯一键 {@code event_id} 加行锁。同一事件的重放请求在数据库层串行，
     * 保证“读状态 → CAS → 写审计”之间不会被另一个重放请求插入。
     */
    VideoStatusOutbox selectByEventIdForUpdate(@Param("eventId") String eventId);

    /**
     * 消费账本状态（0=RECEIVED,1=REDIS_APPLIED_PENDING_FLUSH,2=FLUSHED,3=REPAIR_REQUIRED）。
     * 没有消费记录返回 null；<b>非 null 一律拒绝重放</b>（T07 临时工具从严）。
     */
    Integer selectConsumedProcessStatus(@Param("eventId") String eventId);

    /**
     * 用 <b>数据库时钟</b> 计算 created_at 的年龄（秒）。避免 JVM 与 DB 时区（+8 / UTC）差异
     * 把安全窗口边界算歪；created_at 未来时可能返回负数。
     */
    Long selectCreatedAgeSeconds(@Param("eventId") String eventId);

    /** 幂等键查询：operationId 唯一，已存在的审计行决定“返回原结果”还是“冲突拒绝”。 */
    VideoStatusConsumptionRepairLog selectRepairLogByOperationId(
            @Param("operationId") String operationId);

    /**
     * 单行 CAS：{@code status = 3} 才允许回到 PENDING。
     *
     * <p>保留原 {@code event_id} / {@code payload} / {@code aggregate_id} / {@code event_type}
     * 与 {@code last_error}（失败证据不擦除）；{@code retry_count}、{@code lease_recovery_count}
     * 与 {@code confirm_writeback_count} 归零是“人工重放后重新计量的新预算”，
     * 前后值由调用方持久化到审计元数据。清空租约与下一次重试时间，使事件可被 relay 立刻领取。</p>
     *
     * @return 影响行数，正常必须是 1
     */
    int resetFailedToPending(@Param("eventId") String eventId);

    /**
     * 写 COMPLETED 审计行（{@code last_error} 存 before/after JSON 元数据）；
     * 影响行数必须为 1，否则调用方整体回滚。
     */
    int insertCompletedRepairLog(VideoStatusConsumptionRepairLog log);
}
