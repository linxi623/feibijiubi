package com.feibijiubi.backend.infrastructure.ratelimit.service;

public interface RateLimitService {
    void checkUploadTokenLimit(Integer userId);

    void checkLoginFailureLimit(String username);

    void recordLoginFailure(String username);

    void clearLoginFailures(String username);
}
