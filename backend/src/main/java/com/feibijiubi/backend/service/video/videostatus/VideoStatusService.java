package com.feibijiubi.backend.service.video.videostatus;

import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;

public interface VideoStatusService {
    ApplyResult apply(VideoStatusChangedEvent event);

    VideoStatusChangedEvent createEvent(Integer vid, VideoStatusEventType type, long delta);

    VideoStatus getByVid(Integer vid);

    enum ApplyResult {
        APPLIED,
        DUPLICATE,
        NEEDS_REBUILD,
        NEGATIVE_RESULT,
        INVALID_FIELD,
        INVALID_REDIS_TYPE
    }

    enum DeltaCleanupResult {
        EMPTY,
        REMAINING,
        DUPLICATE_CLEANUP,
        GENERATION_CHANGED,
        NEEDS_REBUILD,
        INVALID_ARGUMENT
    }
}
