package com.feibijiubi.backend.service.video.videostatus;

import com.feibijiubi.backend.entity.VideoStatusFlushBatch;
import com.feibijiubi.backend.event.VideoStatusDelta;

import java.util.List;

public record FlushResult(
        String batchId,
        String redisGeneration,
        Integer vid,
        List<Long> consumedEventIds,
        VideoStatusDelta delta,
        boolean empty
) {
    public FlushResult {
        consumedEventIds = List.copyOf(consumedEventIds);
    }

    public static FlushResult empty(
            Integer vid,
            String redisGeneration
    ) {
        return new FlushResult(
                null,
                redisGeneration,
                vid,
                List.of(),
                VideoStatusDelta.zero(),
                true
        );
    }

    public static FlushResult completed(
            VideoStatusFlushBatch batch,
            List<Long> consumedEventIds,
            VideoStatusDelta delta
    ) {
        return new FlushResult(
                batch.getBatchId(),
                batch.getRedisGeneration(),
                batch.getVid(),
                consumedEventIds,
                delta,
                false
        );
    }
}
