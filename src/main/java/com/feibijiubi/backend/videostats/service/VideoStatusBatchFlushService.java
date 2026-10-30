package com.feibijiubi.backend.videostats.service;

import java.util.List;

public interface VideoStatusBatchFlushService {

    void flushOneVideo(Integer vid, int limit);

    void markRepairRequired(
            List<Long> consumedEventIds,
            String lastError
    );
}
