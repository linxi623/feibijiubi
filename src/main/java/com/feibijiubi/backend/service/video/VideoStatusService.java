package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;

public interface VideoStatusService {
    ApplyResult apply(VideoStatusChangedEvent event);

    VideoStatusChangedEvent createEvent(Integer vid, VideoStatusEventType type, long delta);

    void rebuild(Integer vid);

    void persist(VideoStatusChangedEvent event, String payloadHash);

    VideoStatus getByVid(Integer vid);

    enum ApplyResult {
        APPLIED,
        DUPLICATE,
        NEEDS_REBUILD,
        OLD_SEQUENCE,
        SEQUENCE_GAP,
        NEGATIVE_RESULT,
        INVALID_FIELD
    }
}
