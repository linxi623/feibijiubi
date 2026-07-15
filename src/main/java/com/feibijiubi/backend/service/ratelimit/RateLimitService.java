package com.feibijiubi.backend.service.ratelimit;

public interface RateLimitService {
    void checkUploadTokenLimit(Integer userId);
}
