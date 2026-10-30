package com.feibijiubi.backend.videostats.service;


import com.feibijiubi.backend.videostats.enums.RegistrationResult;
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
