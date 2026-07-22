package com.feibijiubi.backend.service.video.videostatus;


import com.feibijiubi.backend.enums.RegistrationResult;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;

public interface VideoStatusConsumptionService {
    RegistrationResult register(
            VideoStatusChangedEvent event,
            String semanticPayloadHash
    );

    void markRedisApplied(String eventId);

    void recordConsumerFailure(String eventId,
                               int attempt,
                               String lastError);
}
