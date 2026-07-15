package com.feibijiubi.backend.utils.redis.operation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;

import com.feibijiubi.backend.utils.JsonUtils;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RedisSetOperations {

    private final StringRedisTemplate redisTemplate;
    private final JsonUtils jsonUtils;

    /**
     * 添加元素到 Set（存字符串）
     */
    public long add(String key, String... values) {
        Long addedCount = redisTemplate.opsForSet().add(key, values);
        return addedCount == null ? 0L : addedCount;
    }

    /**
     * 添加对象到 Set（自动转 JSON 字符串）
     */
    public long addObject(String key, Object... values) {
        String[] jsonStrings = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            jsonStrings[i] = jsonUtils.toJson(values[i]);
        }
        return add(key, jsonStrings);
    }

    /**
     * 从 Set 中移除元素
     */
    public long remove(String key, String... values) {
        Long removedCount = redisTemplate.opsForSet().remove(key, (Object[]) values);
        return removedCount == null ? 0L : removedCount;
    }

    /**
     * 移除对象（自动转 JSON）
     */
    public long removeObject(String key, Object... values) {
        String[] jsonStrings = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            jsonStrings[i] = jsonUtils.toJson(values[i]);
        }
        return remove(key, jsonStrings);
    }

    /**
     * 判断元素是否在 Set 中（字符串）
     */
    public boolean isMember(String key, String value) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(key, value));
    }

    /**
     * 判断对象是否在 Set 中（自动转 JSON 比较）
     */
    public boolean isMemberObject(String key, Object value) {
        String json = jsonUtils.toJson(value);
        return isMember(key, json);
    }

    /**
     * 获取 Set 中所有元素（返回字符串集合）
     */
    public Set<String> members(String key) {
        Set<String> members = redisTemplate.opsForSet().members(key);
        return members == null ? Collections.emptySet() : members;
    }

    /**
     * 获取 Set 中所有元素，并解析成指定类型的对象集合
     */
    public <T> Set<T> members(String key, Class<T> clazz) {
        Set<String> jsonStrings = members(key);
        if (jsonStrings.isEmpty()) {
            return Collections.emptySet();
        }
        return jsonStrings.stream()
                .map(json -> jsonUtils.fromJson(json, clazz))
                .collect(Collectors.toSet());
    }

    /**
     * 获取 Set 的大小
     */
    public long size(String key) {
        Long size = redisTemplate.opsForSet().size(key);
        return size == null ? 0L : size;
    }
}
