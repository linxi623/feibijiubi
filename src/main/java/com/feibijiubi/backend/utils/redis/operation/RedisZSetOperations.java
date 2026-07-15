package com.feibijiubi.backend.utils.redis.operation;

import com.feibijiubi.backend.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisZSetOperations {

    private final StringRedisTemplate redisTemplate;
    private final JsonUtils jsonUtils;

    public boolean add(String key, Object value, double score) {
        String json = jsonUtils.toJson(value);
        return Boolean.TRUE.equals(redisTemplate.opsForZSet().add(key, json, score));
    }

    public Double increaseScore(String key, Object value, double delta) {
        String json = jsonUtils.toJson(value);
        return redisTemplate.opsForZSet().incrementScore(key, json, delta);
    }

    public Long reverseRank(String key, Object value) {
        return redisTemplate.opsForZSet().reverseRank(key, value);
    }

    public Set<String> reverseRange(String key, long start, long end) {
        Set<String> values = redisTemplate.opsForZSet().reverseRange(key, start, end);

        return values == null ? Collections.emptySet() : values;
    }
}
