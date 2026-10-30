package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.event.VideoStatusChangedEvent;

public interface VideoStatusEventFingerprintService {

    String hash(VideoStatusChangedEvent event);
}
