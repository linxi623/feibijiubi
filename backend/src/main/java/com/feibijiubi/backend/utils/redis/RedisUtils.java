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

    /**
     * 仅当 key 不存在时设置值（带过期时间）
     * 等价于 Redis 命令：SET key value NX EX seconds
     *
     * @param key   键
     * @param value 值
     * @param ttl   过期时间（不能为 null 或负数）
     * @return true 表示设置成功（key 原来不存在），false 表示设置失败（key 已存在）
     */
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return executeRedisOperation(() ->
                Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                        .setIfAbsent(key, value, ttl))
        );
    }

    /**
     * 仅当 key 不存在时设置值（无过期时间）
     * 等价于 Redis 命令：SET key value NX
     *
     * @param key   键
     * @param value 值
     * @return true 表示设置成功（key 原来不存在），false 表示设置失败（key 已存在）
     */
    public boolean setIfAbsent(String key, String value) {
        return executeRedisOperation(() ->
                Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                        .setIfAbsent(key, value))
        );
    }

    /**
     * 仅当 key 不存在时设置 JSON 对象（带过期时间）
     *
     * @param key   键
     * @param value 要序列化的对象
     * @param ttl   过期时间
     * @return true 表示设置成功，false 表示设置失败（key 已存在）
     */
    public boolean setJsonIfAbsent(String key, Object value, Duration ttl) {
        String json = jsonUtils.toJson(value);
        return setIfAbsent(key, json, ttl);
    }

    /**
     * 仅当 key 不存在时设置 JSON 对象（无过期时间）
     */
    public boolean setJsonIfAbsent(String key, Object value) {
        String json = jsonUtils.toJson(value);
        return setIfAbsent(key, json);
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
