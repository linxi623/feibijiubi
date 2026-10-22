package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VideoStatusConsumedEventMapper {
    int insertReceived(VideoStatusConsumedEvent event);

    VideoStatusConsumedEvent selectByEventId(String eventId);

    VideoStatusConsumedEvent selectByEventIdForUpdate(String eventId);

    int touchReceivedAttempt(String eventId);

    int markRedisApplied(String eventId);

    int markCommitted(String eventId);

    int recordConsumerFailure(
            @Param("eventId") String eventId,
            @Param("attempt") int attempt,
            @Param("lastError") String lastError
    );

    List<VideoStatusConsumedEvent> selectExpiredReceivedForUpdate(
            @Param("expiredBefore") LocalDateTime expiredBefore,
            @Param("limit") int limit
    );

    int markRecoveryRepublished(String eventId);

    int markRepairRequired(
            @Param("eventId") String eventId,
            @Param("expectedStatus") int expectedStatus,
            @Param("lastError") String lastError
    );

    int resetRepairToReceived(String eventId);

    int keepRepairIgnored(
            @Param("eventId") String eventId,
            @Param("resolution") String resolution
    );

    List<VideoStatusConsumedEvent> selectRepairRequired(
            @Param("afterId") Long afterId,
            @Param("limit") int limit
    );

    List<VideoStatusConsumedEvent> selectRebuildCandidates(
            @Param("vid") Integer vid
    );

    List<VideoStatusConsumedEvent> selectPendingForUpdate(
            @Param("vid") Integer vid,
            @Param("limit") int limit
    );

    /**
     * 上限侧失败时的有界备选：同一 vid / status 1，改为“负增量优先”。
     *
     * <p>只用于 {@code selectPendingForUpdate} 因上界不足或净量超 long 无法落库时
     * 给合法负增量一次前进机会；调用方仍持有同一 vid 锁、处于同一事务，
     * 且每个 flush 批次最多调用一次。</p>
     */
    List<VideoStatusConsumedEvent> selectPendingNegativeFirstForUpdate(
            @Param("vid") Integer vid,
            @Param("limit") int limit
    );

    int markFlushed(@Param("ids") List<Long> ids);

    int markFlushRepairRequired(
            @Param("ids") List<Long> ids,
            @Param("lastError") String lastError
    );

    int countPendingByVid(@Param("vid") Integer vid);

    List<Integer> selectPendingVids(@Param("limit") int limit);

    /**
     * T11 公平恢复的 keyset 查询：只取 {@code vid > afterVid} 的待刷视频，
     * 按 vid 升序。{@code afterVid == null} 表示从头开始；游标只影响效率，
     * 不改变待刷事件的 status 1 语义，也不删除任何 dirty 标记。
     */
    List<Integer> selectPendingVidsAfter(
            @Param("afterVid") Integer afterVid,
            @Param("limit") int limit
    );

    // ------------------------------------------------------------------ T09

    /**
     * status0 安全补偿候选：只返回仍为 RECEIVED、已过静默期、在安全窗口内、
     * 冷却已到、租约空闲且未超过补偿次数上限的事件。年龄用 DB 时钟计算。
     */
    List<VideoStatusConsumedEvent> selectStatus0CompensationCandidates(
            @Param("minAgeSeconds") long minAgeSeconds,
            @Param("maxAgeSeconds") long maxAgeSeconds,
            @Param("maxAttempts") int maxAttempts,
            @Param("afterId") Long afterId,
            @Param("limit") int limit
    );

    /** 只读 preview：不过滤静默期/安全窗口/冷却，供人工诊断。 */
    List<VideoStatusConsumedEvent> selectStatus0CompensationPreview(
            @Param("afterId") Long afterId,
            @Param("limit") int limit
    );

    long countStatus0OverSafeWindow(
            @Param("maxAgeSeconds") long maxAgeSeconds,
            @Param("cap") int cap
    );

    /** 领取 CAS：仍未应用、租约空闲、冷却到期且未超尝试上限时才成功。 */
    int claimStatus0Compensation(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("leaseSeconds") int leaseSeconds,
            @Param("minAgeSeconds") long minAgeSeconds,
            @Param("maxAgeSeconds") long maxAgeSeconds,
            @Param("maxAttempts") int maxAttempts
    );

    /** 发送失败/证据不足：释放租约并设置冷却，绝不改 process_status。 */
    int releaseStatus0Compensation(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken,
            @Param("cooldownSeconds") long cooldownSeconds,
            @Param("lastError") String lastError
    );

    /** 原 eventId 已重新投递：只回写补偿元数据，process_status 仍由消费者推进。 */
    int markStatus0CompensationRepublished(
            @Param("eventId") String eventId,
            @Param("leaseToken") String leaseToken
    );
}
