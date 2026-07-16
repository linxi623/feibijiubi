package com.feibijiubi.backend.utils.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;


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
        executeRedisOperation(() ->
                stringRedisTemplate.opsForValue().set(key, value, ttl)
        );
    }

    public void setString(String key, String value) {
        executeRedisOperation(() ->
                stringRedisTemplate.opsForValue().set(key, value)
        );
    }

    public String getString(String key) {
        return executeRedisOperation(() ->
                stringRedisTemplate.opsForValue().get(key)
        );
    }

    public void setJson(String key, Object value) {
        String json = jsonUtils.toJson(value);
        setString(key, json);
    }

    public void setJson(
            String key,
            Object value,
            Duration ttl
    ) {
        String json = jsonUtils.toJson(value);
        setString(key, json, ttl);
    }

    public <T> T getJson(String key, Class<T> type) {
        String json = getString(key);

        if (json == null) {
            return null;
        }
        return jsonUtils.fromJson(json, type);
    }

    public <T> T getJson(
            String key,
            TypeReference<T> typeReference
    ) {
        String json = getString(key);

        if (json == null) {
            return null;
        }
        return jsonUtils.fromJson(json, typeReference);
    }

    public boolean delete(String key) {
        return executeRedisOperation(() ->
                Boolean.TRUE.equals(stringRedisTemplate.delete(key))
        );
    }

    public boolean hasKey(String key) {
        return executeRedisOperation(() ->
                Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))
        );
    }

    public boolean expire(String key, Duration ttl) {
        return executeRedisOperation(() ->
                Boolean.TRUE.equals(stringRedisTemplate.expire(key, ttl))
        );
    }

    public Long getExpire(String key, TimeUnit timeUnit) {
        return executeRedisOperation(() ->
                stringRedisTemplate.getExpire(key, timeUnit)
        );
    }

    public <T> T executeScript(
            RedisScript<T> script,
            List<String> keys,
            Object... args
    ) {
        return executeRedisOperation(() ->
                stringRedisTemplate.execute(script, keys, args)
        );
    }

    private <T> T executeRedisOperation(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            throw new RedisOperationException("Redis 操作失败", e);
        }
    }

    private void executeRedisOperation(Runnable operation) {
        try {
            operation.run();
        } catch (DataAccessException e) {
            throw new RedisOperationException("Redis 操作失败", e);
        }
    }
}
