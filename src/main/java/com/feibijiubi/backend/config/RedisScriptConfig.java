package com.feibijiubi.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
public class RedisScriptConfig {

    @Bean
    @Primary
    public DefaultRedisScript<Long> fixedWindowRateLimitScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(
                new ClassPathResource("lua/fixed-window-rate-limit.lua")
        );
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    @Bean
    @Primary
    DefaultRedisScript<String> videoStatusIncrementScript() {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(
                new ClassPathResource("lua/video-status-increment.lua")
        );
        redisScript.setResultType(String.class);
        return redisScript;
    }

    @Bean
    DefaultRedisScript<String> videoStatusInitScript() {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(
                new ClassPathResource("lua/video-status-init.lua")
        );
        redisScript.setResultType(String.class);
        return redisScript;
    }

    @Bean
    DefaultRedisScript<String> videoStatusDeltaSubtractScript() {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(
                new ClassPathResource("lua/video-status-delta-subtract.lua")
        );
        redisScript.setResultType(String.class);
        return redisScript;
    }

    @Bean
    DefaultRedisScript<Long> compareAndDeleteScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(
                new ClassPathResource("lua/compare-and-delete.lua")
        );
        redisScript.setResultType(Long.class);
        return redisScript;
    }
}
