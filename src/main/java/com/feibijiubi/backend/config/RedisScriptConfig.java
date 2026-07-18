package com.feibijiubi.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

@Configuration
public class RedisScriptConfig {

    @Bean
    public DefaultRedisScript<Long> fixedWindowRateLimitScript() {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(
                new ClassPathResource("lua/fixed-window-rate-limit.lua")
        );
        redisScript.setResultType(Long.class);
        return redisScript;
    }

    @Bean DefaultRedisScript<String> videoStatusIncrementScript() {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(
                new ClassPathResource("lua/video-status-increment.lua")
        );
        redisScript.setResultType(String.class);
        return redisScript;
    }
}
