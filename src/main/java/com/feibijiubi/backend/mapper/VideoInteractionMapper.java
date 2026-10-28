package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.VideoInteractionRequest;
import com.feibijiubi.backend.entity.VideoInteractionWindow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * T15 交互幂等/窗口表。所有语句都带 {@code timeout=3}，与 Outbox/消费账本一致：
 * 慢 SQL 由 MyBatis 打断，不把请求线程挂在数据库上。
 */
@Mapper
public interface VideoInteractionMapper {

    /** 幂等读取：{@code request_key} 列是 utf8mb4_bin，所以查询大小写敏感。 */
    VideoInteractionRequest selectRequestByKey(@Param("requestKey") String requestKey);

    /** 插入 PENDING 占位行；唯一键冲突由 Spring 翻译为 {@code DuplicateKeyException}。 */
    int insertRequest(VideoInteractionRequest request);

    /**
     * 只保证窗口行存在，<b>绝不</b>初始化或推进 {@code last_counted_at}。
     *
     * <p>返回值的 MySQL 语义是 1=插入、0=命中已存在且无变化、2=命中且有变化，本语句在
     * 命中分支是自赋值（无变化），因此返回 0 或 1 都正常，调用方不得据此判断成败。</p>
     */
    int ensureWindowRow(
            @Param("actorType") String actorType,
            @Param("actorHash") String actorHash,
            @Param("vid") Integer vid,
            @Param("action") String action
    );

    /** 锁定并读取窗口行（行锁持有到事务结束），用 DB 时钟之外的唯一真相是库里的值。 */
    VideoInteractionWindow selectWindowForUpdate(
            @Param("actorType") String actorType,
            @Param("actorHash") String actorHash,
            @Param("vid") Integer vid,
            @Param("action") String action
    );

    /**
     * 到期判定与推进合并成一条守卫更新（DB 时钟）：{@code last_counted_at IS NULL}
     * 或 {@code <= NOW(3) - windowMinutes} 时才推进并返回 1，否则返回 0（抑制，不写任何列）。
     */
    int advanceWindowIfDue(
            @Param("id") Long id,
            @Param("windowMinutes") int windowMinutes
    );

    /** 写请求终态（CAS：只有 PENDING 才能推进），{@code eventId} 同步路径传 null。 */
    int completeRequest(
            @Param("id") Long id,
            @Param("state") int state,
            @Param("eventId") String eventId
    );

    // ---------------------------------------------------------------- T15 清理

    /**
     * 有界候选：按 {@code state} 单值取已过期终态请求行的主键，按 {@code (completed_at, id)} 升序。
     *
     * <p>{@code FORCE INDEX} 是故意的：缺索引时 MySQL 直接报 1176，而不是静默退化成
     * “全表扫 + filesort + LIMIT”在增长表上跑满 {@code timeout=3}。索引名见
     * {@code database/migrations/t15-video-interaction-cleanup-indexes.sql}。</p>
     *
     * <p>本查询<b>不加锁</b>（非锁定一致读），所以拿到的 id 在执行 DELETE 之前可能已经变化，
     * 删除时必须重新判定时间/状态守卫。</p>
     */
    List<Long> selectExpiredRequestIds(
            @Param("state") int state,
            @Param("retentionDays") int retentionDays,
            @Param("limit") int limit
    );

    /**
     * 按主键删除已过期终态请求行，并<b>原样重复</b>时间 + 状态守卫。
     *
     * <p>守卫不是形式主义：候选读与删除之间没有事务，若只按 id 删，就可能删掉一个刚在别的事务里
     * 被推进的行。空集合一律 fail closed（{@code AND 1 = 0}，删 0 行），不会退化成无 {@code WHERE} 的整表删除。</p>
     */
    int deleteExpiredRequestsByIds(
            @Param("ids") List<Long> ids,
            @Param("retentionDays") int retentionDays
    );

    /**
     * 有界候选：取“连续 N 天没有计数”的窗口行主键，按 {@code (last_counted_at, id)} 升序。
     *
     * <p>{@code last_counted_at IS NULL}（从未计数）<b>永不</b>进入候选：删掉它等价于把窗口重置，
     * 会静默绕过下一次的 30 分钟判定。索引与 {@code FORCE INDEX} 的理由同上。</p>
     */
    List<Long> selectIdleWindowIds(
            @Param("retentionDays") int retentionDays,
            @Param("limit") int limit
    );

    /** 按主键删除空闲窗口行，守卫（非 NULL 且仍过期）与空集合 fail closed 的处理同请求行。 */
    int deleteIdleWindowsByIds(
            @Param("ids") List<Long> ids,
            @Param("retentionDays") int retentionDays
    );
}
