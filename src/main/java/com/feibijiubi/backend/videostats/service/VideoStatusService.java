package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.videostats.entity.VideoStatus;
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
        COUNTER_OUT_OF_RANGE,
        INVALID_FIELD,
        INVALID_REDIS_TYPE
    }

}
