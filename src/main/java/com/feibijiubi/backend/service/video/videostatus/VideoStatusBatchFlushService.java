package com.feibijiubi.backend.service.video.videostatus;

import java.util.List;

public interface VideoStatusBatchFlushService {

    void flushOneVideo(Integer vid, int limit);

    void markRepairRequired(
            List<Long> consumedEventIds,
            String lastError
    );
}
