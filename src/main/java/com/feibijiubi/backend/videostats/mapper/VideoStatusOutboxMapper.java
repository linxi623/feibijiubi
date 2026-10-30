package com.feibijiubi.backend.videostats.mapper;


import com.feibijiubi.backend.videostats.entity.VideoStatusOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VideoStatusOutboxMapper {
    int insert(VideoStatusOutbox outbox);

    List<VideoStatusOutbox> selectPendingForUpdate(
            @Param("limit") int limit,
            @Param("now")LocalDateTime now
    );

    int markSending(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("sendingAt") LocalDateTime sendingAt
    );

    int markSent(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("sentAt") LocalDateTime sentAt
    );

    int markPending(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("lastError") String lastError
    );

    int markFailed(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("lastError") String lastError
    );

    /**
     * T07：confirm 已成功、markSent 回写失败时回到 PENDING 等重投。
     *
     * <p>本语句只承担“发布失败次数”和“租约恢复次数”之外的第三份独立预算：
     * {@code confirm_writeback_count + 1}。第 10 次仍无法成功回写时直接置 FAILED，
     * 原因固定为 {@code confirm writeback budget exhausted}，避免“MQ 已确认、markSent 永远失败、
     * markConfirmedPending 永远成功”导致每秒永久重投。<b>不</b>增加 {@code retry_count}
     * （发布并没有失败），也<b>不</b>增加 {@code lease_recovery_count}（不是租约过期）。</p>
     *
     * <p>CAS 条件仍是 {@code event_id + status=1 + lease_token}：旧 owner 超时/被接管后无法写回；
     * lease token 清空后重复调用返回 0 行。eventId 与 payload 从不重写，重复投递由消费端幂等吸收。</p>
     */
    int markConfirmedPending(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("lastError") String lastError
    );

    /**
     * T07：confirm 已成功、markSent 回写失败次数耗尽时，形成可诊断终态。
     *
     * <p>与 {@link #markConfirmedPending} 使用同一份 {@code confirm_writeback_count} 预算，
     * 只把状态置为 FAILED（不改 {@code retry_count} / {@code lease_recovery_count}），
     * 并写入固定原因 {@code confirm writeback budget exhausted}。管理员只有在确认 MQ 已收到
     * 该 eventId 后，才能通过受审计重放（保留原 eventId）重新开启预算。</p>
     */
    int markConfirmWritebackExhausted(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("lastError") String lastError
    );

    /**
     * T07：消息本身非法（例如 payload 无法解析）直接 FAILED，不再无限重投。
     *
     * <p>同样不增加 {@code retry_count}：这不是一次“发布失败”，而是永久性投递拒绝；
     * CAS 条件与 {@link #markFailed} 一致，旧 owner 不能覆盖被接管后的状态。</p>
     */
    int markInvalidFailed(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("lastError") String lastError
    );

    /**
     * 恢复租约过期的 SENDING 行（签名保持 T06 不变）。
     *
     * <p>T07 起：租约过期<b>只</b>增加独立的 {@code lease_recovery_count}，不碰
     * {@code retry_count}（后者只统计发布失败）。未耗尽恢复预算时回到 PENDING 且
     * {@code next_retry_at = NOW(3) + 1s}；第 10 次过期直接 FAILED，原因是
     * {@code lease recovery budget exhausted}。每轮最多 1000 行，按
     * {@code sending_at, id} 排序，避免一条 SQL 无限 UPDATE 大表。</p>
     */
    int recoverExpiredSending(
            @Param("expiredBefore") LocalDateTime expiredBefore
    );
}
