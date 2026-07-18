package com.feibijiubi.backend.mapper;


import com.feibijiubi.backend.entity.VideoStatusOutbox;
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

    int recoverExpiredSending(
            @Param("expiredBefore") LocalDateTime expiredBefore
    );
}
