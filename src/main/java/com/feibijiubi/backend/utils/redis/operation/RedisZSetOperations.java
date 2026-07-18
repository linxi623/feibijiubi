package com.feibijiubi.backend.utils.redis.operation;

import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class RedisZSetOperations {

    private final StringRedisTemplate redisTemplate;
    private final JsonUtils jsonUtils;

    public boolean addNewMember(String key, Object value, double score) {
        validateKey(key);
        validateMember(value);
        validateFiniteNumber(score, "Redis ZSet score 必须是有限数");

        String member = serializeMember(value);
        return executeRedisOperation(() ->
                Boolean.TRUE.equals(redisTemplate.opsForZSet().add(key, member, score))
        );
    }

    public long remove(String key, Object value) {
        validateKey(key);
        validateMember(value);

        String member = serializeMember(value);
        Long removed = executeRedisOperation(() ->
                redisTemplate.opsForZSet().remove(key, member)
        );

        return removed == null ? 0L : removed;
    }

    public Double increaseScore(String key, Object value, double delta) {
        validateKey(key);
        validateMember(value);
        validateFiniteNumber(delta, "Redis ZSet delta 必须是有限数");

        String member = serializeMember(value);
        return executeRedisOperation(() ->
                redisTemplate.opsForZSet()
                        .incrementScore(key, member, delta)
        );
    }

    public Long reverseRank(String key, Object value) {
        validateKey(key);
        validateMember(value);

        String member = serializeMember(value);
        return executeRedisOperation(() ->
                redisTemplate.opsForZSet().reverseRank(key, member)
        );
    }

    public List<String> reverseRange(String key, long start, long end) {
        validateKey(key);
        Assert.isTrue(start >= 0, "Redis ZSet start 不能小于 0");
        Assert.isTrue(end >= start, "Redis ZSet end 不能小于 start");

        Set<String> values = executeRedisOperation(() ->
                redisTemplate.opsForZSet()
                        .reverseRange(key, start, end)
        );

        return values == null
                ? Collections.emptyList()
                : List.copyOf(values);
    }

    private String serializeMember(Object value) {
        return jsonUtils.toJson(value);
    }

    private void validateKey(String key) {
        Assert.hasText(key, "Redis key 不能为空");
    }

    private void validateMember(Object value) {
        Objects.requireNonNull(value, "Redis ZSet member 不能为空");
    }

    private void validateFiniteNumber(double value, String message) {
        Assert.isTrue(Double.isFinite(value), message);
    }

    private void executeRedisOperation(Runnable operation) {
        try {
            operation.run();
        } catch (DataAccessException e) {
            throw new RedisOperationException("Redis ZSet 操作失败", e);
        }
    }

    private <T> T executeRedisOperation(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            throw new RedisOperationException("Redis ZSet 操作失败", e);
        }
    }
}
