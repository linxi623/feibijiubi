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

    int markFlushed(@Param("ids") List<Long> ids);

    int markFlushRepairRequired(
            @Param("ids") List<Long> ids,
            @Param("lastError") String lastError
    );

    int countPendingByVid(@Param("vid") Integer vid);

    List<Integer> selectPendingVids(@Param("limit") int limit);
}
