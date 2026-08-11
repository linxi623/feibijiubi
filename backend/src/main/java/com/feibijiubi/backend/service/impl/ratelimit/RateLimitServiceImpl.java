package com.feibijiubi.backend.service.impl.ratelimit;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.service.ratelimit.RateLimitService;
import com.feibijiubi.backend.utils.redis.RedisConstants;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class RateLimitServiceImpl implements RateLimitService {
    private final DefaultRedisScript<Long> redisScript;
    private final RedisUtils redisUtils;

    @Override
    public void checkUploadTokenLimit(Integer userId) {
        if(userId == null) {
            throw new BusinessException(401, "登录失效");
        }

        String key = RedisKeyUtils.rateUpload(userId);

        Long current = redisUtils.executeScript(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(RedisConstants.RATE_UPLOAD_WINDOW_SECONDS)
        );

        if(current == null) {
            throw new BusinessException(500, "限流结果异常");
        }

        if(current > RedisConstants.RATE_UPLOAD_MAX_REQUESTS) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
    }

    @Override
    public void checkLoginFailureLimit(String username) {
        String key = RedisKeyUtils.loginFailTimes(username);
        String current = redisUtils.getString(key);

        if (current == null) {
            return;
        }

        final long failureCount;
        try {
            failureCount = Long.parseLong(current);
        } catch (NumberFormatException e) {
            throw new BusinessException(500, "登录失败次数数据异常");
        }

        if (failureCount >= RedisConstants.LOGIN_FAIL_MAX_TIMES) {
            throw new BusinessException(429, "尝试次数过多，请稍后再试");
        }
    }

    @Override
    public void recordLoginFailure(String username) {
        String key = RedisKeyUtils.loginFailTimes(username);

        Long current = redisUtils.executeScript(
                redisScript,
                Collections.singletonList(key),
                String.valueOf(RedisConstants.LOGIN_FAIL_EXPIRE_TIME)
        );

        if(current == null) {
            throw new BusinessException(500, "限流结果异常");
        }
    }

    @Override
    public void clearLoginFailures(String username) {
        String key = RedisKeyUtils.loginFailTimes(username);
        redisUtils.delete(key);
    }
}
