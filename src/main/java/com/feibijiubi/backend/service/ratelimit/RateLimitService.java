package com.feibijiubi.backend.service.ratelimit;

public interface RateLimitService {
    void checkUploadTokenLimit(Integer userId);

    void checkLoginFailureLimit(String username);

    void recordLoginFailure(String username);

    void clearLoginFailures(String username);
}
