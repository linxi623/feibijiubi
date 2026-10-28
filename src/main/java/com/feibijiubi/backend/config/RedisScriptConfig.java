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
    DefaultRedisScript<Long> videoStatusReleaseGateScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/video-status-release-gate.lua"));
        script.setResultType(Long.class);
        return script;
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

    /**
     * T12-C1：覆盖“已存在且完整”的统计 Hash 的内部原语。
     *
     * <p>只有 bean 与脚本，没有任何生产调用者：强制重建仍需管理员权限、审计与并发协调，
     * 本阶段不提供 HTTP 或 Java 业务入口，也不注册任何自动调用。</p>
     */
    @Bean
    DefaultRedisScript<String> videoStatusReplaceScript() {
        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>();
        redisScript.setLocation(
                new ClassPathResource("lua/video-status-replace.lua")
        );
        redisScript.setResultType(String.class);
        return redisScript;
    }

}
