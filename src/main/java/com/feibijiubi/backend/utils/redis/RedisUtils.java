package com.feibijiubi.backend.utils.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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

    /**
     * 有界批次 pipeline EXISTS：一次网络往返取回每个 key 的存在性，结果顺序与入参严格一致。
     *
     * <p>逐 key 结果不能被“存在的总数”替代，调用方必须按位置判定每条证据。
     * 返回条数不符、元素为 null、或元素不是布尔/整型 0 或 1 时抛
     * {@link RedisOperationException}，让调用方 fail closed（不发布缺证据的结果），
     * 而不是把未知当成“不存在”。</p>
     *
     * @param keys 本批 key（调用方负责分批；空列表不发起 Redis 请求）
     * @return 与 keys 等长、同序的存在性结果
     */
    public List<Boolean> hasKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<Object> raw = executeRedisOperation(() ->
                stringRedisTemplate.executePipelined(
                        (RedisCallback<Object>) connection -> {
                            for (String key : keys) {
                                connection.keyCommands().exists(
                                        key.getBytes(StandardCharsets.UTF_8)
                                );
                            }
                            return null;
                        }
                )
        );
        if (raw == null || raw.size() != keys.size()) {
            throw new RedisOperationException(
                    "Redis pipeline EXISTS 返回条数与请求不一致: expected="
                            + keys.size()
                            + ", actual=" + (raw == null ? "null" : raw.size())
            );
        }
        List<Boolean> results = new ArrayList<>(raw.size());
        for (Object value : raw) {
            results.add(existsResult(value));
        }
        return results;
    }

    /**
     * 把单条 pipeline EXISTS 回复严格归一成布尔值。
     *
     * <p>Redis 对 EXISTS 只回整数 0/1，Lettuce 用 {@code Long} 承载；这里额外容忍
     * {@code Boolean}（模板开启事务/比较器时的映射）和其余整型包装类，但值必须精确等于
     * 0 或 1。浮点（含 0.5、1.9、NaN）、小数、以及会被 {@code longValue()} 截断到 0/1
     * 的超范围整数（如 2^64+1 的 {@code BigInteger}）一律拒绝，绝不把未知结果猜成
     * “不存在”。</p>
     */
    private Boolean existsResult(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            long numeric = ((Number) value).longValue();
            if (numeric == 0L) {
                return Boolean.FALSE;
            }
            if (numeric == 1L) {
                return Boolean.TRUE;
            }
        }
        throw new RedisOperationException(
                "Redis pipeline EXISTS 返回无法识别的结果: " + value
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
