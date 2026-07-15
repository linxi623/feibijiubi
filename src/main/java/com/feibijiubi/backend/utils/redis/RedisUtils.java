package com.feibijiubi.backend.utils.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.feibijiubi.backend.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;


@Component
@RequiredArgsConstructor
public class RedisUtils {

    private final StringRedisTemplate stringRedisTemplate;
    private final JsonUtils jsonUtils;

    public void setString(
            String key,
            String value,
            Duration ttl
    ) {
        stringRedisTemplate.opsForValue().set(key, value, ttl);
    }

    public void setString(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    public String getString(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public void setJson(String key, Object value) {
        String json = jsonUtils.toJson(value);
        stringRedisTemplate.opsForValue().set(key, json);
    }

    public void setJson(
            String key,
            Object value,
            Duration ttl
    ) {
        String json = jsonUtils.toJson(value);
        stringRedisTemplate.opsForValue().set(key, json, ttl);
    }

    public <T> T getJson(String key, Class<T> type) {
        String json = stringRedisTemplate.opsForValue().get(key);

        if (json == null) {
            return null;
        }
        return jsonUtils.fromJson(json, type);
    }

    public <T> T getJson(
            String key,
            TypeReference<T> typeReference
    ) {
        String json = stringRedisTemplate.opsForValue().get(key);

        if (json == null) {
            return null;
        }
        return jsonUtils.fromJson(json, typeReference);
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.delete(key));
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    public boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(stringRedisTemplate.expire(key, ttl));
    }

    public Long getExpire(String key, TimeUnit timeUnit) {
        return stringRedisTemplate.getExpire(key, timeUnit);
    }
}

