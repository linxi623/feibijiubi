package com.feibijiubi.backend.infrastructure.ratelimit.service.impl;

import com.feibijiubi.backend.infrastructure.ratelimit.service.RateLimitService;
import com.feibijiubi.backend.infrastructure.redis.RedisUtils;
import com.feibijiubi.backend.infrastructure.redis.config.RedisScriptConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * R5：验证通用限流服务搬进 {@code infrastructure.ratelimit} 后仍按原 bean 名装配，且脚本注入仍然唯一。
 *
 * <p>本类<b>不</b>连 Redis：{@link RedisUtils} 是 mock，脚本 bean 来自真实的 {@link RedisScriptConfig}。
 * 因此它证明的是装配事实——{@code RateLimitServiceImpl} 那个按类型注入的
 * {@code DefaultRedisScript<Long>} 仍然解析到 {@code @Primary} 的 {@code fixedWindowRateLimitScript}，
 * 而不是被 {@code videoStatusReleaseGateScript} 抢走。脚本执行语义由
 * {@link RateLimitServiceImplTests} 覆盖。</p>
 */
class RateLimitServiceWiringTests {

    /** 按生产路径装配：组件扫描负责实现类与 bean 名，脚本 bean 来自真实的 RedisScriptConfig。 */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = RateLimitServiceImpl.class)
    @Import(RedisScriptConfig.class)
    static class RateLimitWiringConfiguration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RateLimitWiringConfiguration.class)
            .withBean(RedisUtils.class, () -> mock(RedisUtils.class));

    @Test
    void rateLimitServiceIsWiredOnceUnderItsProductionBeanName() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));

            assertEquals(1, context.getBeanNamesForType(RateLimitService.class).length,
                    "限流服务必须唯一");
            assertTrue(context.containsBean("rateLimitServiceImpl"),
                    "限流实现必须仍叫 rateLimitServiceImpl");
            assertInstanceOf(RateLimitServiceImpl.class, context.getBean(RateLimitService.class));

            assertNoInfrastructure(context);
        });
    }

    @Test
    void injectedScriptIsStillThePrimaryFixedWindowScript() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));

            Object service = context.getBean("rateLimitServiceImpl");
            Object injected = ReflectionTestUtils.getField(service, "redisScript");
            assertSame(context.getBean("fixedWindowRateLimitScript"), injected,
                    "限额脚本仍必须是 @Primary 的 fixedWindowRateLimitScript");
        });
    }

    @Test
    void movedClassHasNoLegacyDuplicate() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.feibijiubi.backend.service.ratelimit.RateLimitService"),
                "旧包不得残留接口（同一类只存在一份）");
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.feibijiubi.backend.service.impl.ratelimit.RateLimitServiceImpl"),
                "旧包不得残留实现（同一类只存在一份）");
    }

    private static void assertNoInfrastructure(AssertableApplicationContext context) {
        assertEquals(0, context.getBeanNamesForType(DataSource.class).length,
                "不得创建 DataSource");
        assertEquals(0, context.getBeanNamesForType(RedisConnectionFactory.class).length,
                "不得创建 Redis 连接工厂");
        assertEquals(0, context.getBeanNamesForType(ConnectionFactory.class).length,
                "不得创建 RabbitMQ 连接工厂");
    }

    private static String failureMessage(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        return failure == null ? "" : failure.toString();
    }
}
