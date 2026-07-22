package com.feibijiubi.backend.service.video.videostatus;

import java.util.List;

public interface VideoStatusBatchFlushService {

    FlushResult flushOneVideo(
            Integer vid,
            int limit,
            String redisGeneration
    );

    void markRepairRequired(
            List<Long> consumedEventIds,
            String lastError
    );
}
