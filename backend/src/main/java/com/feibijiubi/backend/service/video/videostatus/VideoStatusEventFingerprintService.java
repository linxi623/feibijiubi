package com.feibijiubi.backend.service.video.videostatus;

import com.feibijiubi.backend.event.VideoStatusChangedEvent;

public interface VideoStatusEventFingerprintService {

    String hash(VideoStatusChangedEvent event);
}
