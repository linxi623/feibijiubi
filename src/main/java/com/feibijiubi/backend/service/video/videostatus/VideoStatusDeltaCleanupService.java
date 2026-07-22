package com.feibijiubi.backend.service.video.videostatus;

public interface VideoStatusDeltaCleanupService {

    void cleanup(String batchId);

    int retryPending(int limit);
}
