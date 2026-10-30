package com.feibijiubi.backend.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.infrastructure.redis.RedisOperationException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5：验证 {@link GlobalExceptionHandler} 搬进 {@code shared} 后仍然是“一个 Spring 组件、一套异常映射”。
 *
 * <p>本类<b>不</b>加载 {@code BackendApplication}、不导入自动配置、不读 {@code application.yml}，
 * 因此不会创建 DataSource / Redis / RabbitMQ 连接；上下文只扫描 {@code shared} 包。
 * 与 {@link GlobalExceptionHandlerTests} 的分工：那个类直接 new 处理器验证状态码映射，
 * 本类验证“按注解真的能被扫描注册”和“异常类型 → 处理方法”的绑定关系没变。</p>
 */
class GlobalExceptionHandlerWiringTests {

    private static final String OLD_HANDLER_FQCN = "com.feibijiubi.backend.common.GlobalExceptionHandler";

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = GlobalExceptionHandler.class)
    static class SharedOnlyConfiguration {
    }

    /** 只扫描 shared 包；{@link JsonUtils} 需要 ObjectMapper，这里给一个空 mapper，不连任何外部系统。 */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SharedOnlyConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void adviceIsDiscoveredByComponentScanUnderItsProductionBeanName() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));

            assertEquals(1, context.getBeanNamesForType(GlobalExceptionHandler.class).length,
                    "全局异常处理器必须唯一");
            assertTrue(context.containsBean("globalExceptionHandler"),
                    "组件名不得因搬包改变（默认 bean 名来自类名）");
            assertInstanceOf(GlobalExceptionHandler.class, context.getBean("globalExceptionHandler"));
            assertEquals(1, context.getBeansWithAnnotation(RestControllerAdvice.class).size(),
                    "全局 advice 只有一个");
            assertTrue(context.containsBean("jsonUtils"), "同包的通用 JSON 组件也要在扫描范围内");

            assertNoInfrastructure(context);
        });
    }

    @Test
    void exceptionTypeToHandlerMappingIsUnchanged() {
        ExceptionHandlerMethodResolver resolver =
                new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        Method business = resolver.resolveMethod(new BusinessException(401, "请先登录"));
        assertNotNull(business, "BusinessException 仍必须走业务异常分支");
        assertEquals("handleBusinessException", business.getName());

        Method redis = resolver.resolveMethod(new RedisOperationException("缓存不可用"));
        assertNotNull(redis, "RedisOperationException 仍必须走 503 分支");
        assertEquals("handleRedisOperationException", redis.getName());

        Method fallback = resolver.resolveMethod(new IllegalStateException("boom"));
        assertNotNull(fallback, "未分类异常仍必须落到兜底分支");
        assertEquals("handleException", fallback.getName());
    }

    @Test
    void rootPackageScanSeesExactlyTheSharedAdviceAndNoLegacyClass() {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(RestControllerAdvice.class));

        Set<String> names = new LinkedHashSet<>();
        provider.findCandidateComponents("com.feibijiubi.backend")
                .forEach(candidate -> names.add(candidate.getBeanClassName()));

        assertEquals(Set.of(GlobalExceptionHandler.class.getName()), names,
                "根包扫描（@SpringBootApplication）必须包含 shared 下的 advice，且只能有一份");
        assertThrows(ClassNotFoundException.class, () -> Class.forName(OLD_HANDLER_FQCN),
                "旧包不得残留同类（同一类只存在一份）");
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
