package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VideoStatusConsumedEventMapper {
    int insertReceived(VideoStatusConsumedEvent event);

    VideoStatusConsumedEvent selectByEventId(String eventId);

    int markCommitted(String eventId);

    int markRepairRequired(
            @Param("eventId") String eventId,
            @Param("lastError") String lastError
    );
}
