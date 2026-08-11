package com.feibijiubi.backend.utils.redis.operation;

import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class RedisHashOperations {

    private final StringRedisTemplate redisTemplate;
    private final JsonUtils jsonUtils;

    public Map<String, String> entries(String key) {
        validateKey(key);

        Map<String, String> entries = executeRedisOperation(() ->
                hashOperations().entries(key)
        );

        return entries == null || entries.isEmpty()
                ? Collections.emptyMap()
                : Map.copyOf(entries);
    }

    public String get(String key, String field) {
        validateKey(key);
        validateField(field);

        return executeRedisOperation(() ->
                hashOperations().get(key, field)
        );
    }

    public List<String> multiGet(
            String key,
            List<String> fields
    ) {
        validateKey(key);
        Assert.notEmpty(fields, "Redis Hash fields 不能为空");
        fields.forEach(this::validateField);

        List<String> values = executeRedisOperation(() ->
                hashOperations().multiGet(key, fields)
        );

        return values == null
                ? Collections.emptyList()
                : values;
    }

    public void put(
            String key,
            String field,
            Object value
    ) {
        validateKey(key);
        validateField(field);
        Objects.requireNonNull(value, "Redis Hash value 不能为空");

        String serializedValue = serialize(value);

        executeRedisOperation(() ->
                hashOperations().put(
                        key,
                        field,
                        serializedValue
                )
        );
    }

    public void putAll(
            String key,
            Map<String, ?> values
    ) {
        validateKey(key);
        Assert.notEmpty(values, "Redis Hash values 不能为空");

        Map<String, String> serializedValues = new LinkedHashMap<>();

        values.forEach((field, value) -> {
            validateField(field);
            Objects.requireNonNull(value, "Redis Hash value 不能为空");

            serializedValues.put(
                    field,
                    serialize(value)
            );
        });

        executeRedisOperation(() ->
                hashOperations().putAll(
                        key,
                        serializedValues
                )
        );
    }

    public long deleteFields(
            String key,
            String... fields
    ) {
        validateKey(key);
        Assert.notEmpty(fields, "Redis Hash fields 不能为空");

        for (String field : fields) {
            validateField(field);
        }

        Long deleted = executeRedisOperation(() ->
                hashOperations().delete(
                        key,
                        (Object[]) fields
                )
        );

        return deleted == null ? 0L : deleted;
    }

    private String serialize(Object value) {
        return jsonUtils.toJson(value);
    }

    private HashOperations<String, String, String>
    hashOperations() {
        return redisTemplate.opsForHash();
    }

    private void validateKey(String key) {
        Assert.hasText(key, "Redis key 不能为空");
    }

    private void validateField(String field) {
        Assert.hasText(field, "Redis Hash field 不能为空");
    }

    private void executeRedisOperation(Runnable operation) {
        try {
            operation.run();
        } catch (DataAccessException e) {
            throw new RedisOperationException(
                    "Redis Hash 操作失败",
                    e
            );
        }
    }

    private <T> T executeRedisOperation(
            Supplier<T> operation
    ) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            throw new RedisOperationException(
                    "Redis Hash 操作失败", e
            );
        }
    }
}
