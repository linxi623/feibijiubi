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
}
