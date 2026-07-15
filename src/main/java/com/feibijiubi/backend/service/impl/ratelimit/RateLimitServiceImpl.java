package com.feibijiubi.backend.service.impl.ratelimit;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.service.ratelimit.RateLimitService;
import com.feibijiubi.backend.utils.redis.RedisConstants;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class RateLimitServiceImpl implements RateLimitService {
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> redisScript;

    @Override
    public void checkUploadTokenLimit(Integer userId) {
        if(userId == null) {
            throw new BusinessException(401, "登录失效");
        }

        String key = RedisKeyUtils.rateUpload(userId);

        final Long current;
        try {
            current = redisTemplate.execute(
                    redisScript,
                    Collections.singletonList(key),
                    String.valueOf(RedisConstants.RATE_UPLOAD_WINDOW_SECONDS)
            );
        } catch (DataAccessException e) {
            throw new BusinessException(503, "限流服务暂不可用，请稍后再试");
        }

        if(current == null) {
            throw new BusinessException(500, "限流结果异常");
        }

        if(current > RedisConstants.RATE_UPLOAD_MAX_REQUESTS) {
            throw new BusinessException(429, "请求过于频繁，请稍后再试");
        }
    }
}
