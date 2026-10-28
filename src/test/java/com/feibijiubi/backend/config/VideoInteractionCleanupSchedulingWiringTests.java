package com.feibijiubi.backend.config;

import com.feibijiubi.backend.service.impl.video.interaction.VideoInteractionCleanupScheduler;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionCleanupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T15 清理调度的真实 Spring 上下文回归（无 DB、无完整应用、无 Spring Boot 自动配置）。
 *
 * <p>这条用例存在的原因是一个真实启动阻塞：{@code @Component VideoInteractionCleanupScheduler} 的默认 bean 名是
 * {@code videoInteractionCleanupScheduler}，而线程池 bean 曾经也注册成同名，于是出现两个同名 bean 定义。
 * Spring Boot 默认关闭 bean 覆盖（{@code allow-bean-definition-overriding=false}），应用会在启动时抛
 * {@code BeanDefinitionOverrideException}。单测（Mockito）与文档都看不见这一点，只有真实上下文 refresh 能看见。</p>
 *
 * <p>因此这里：同时注册 {@link VideoInteractionCleanupSchedulingConfig} 与调度组件、提供 mock 依赖、**显式关闭
 * bean 覆盖**、执行真实 {@code refresh()}，并验证两个 bean 的类型/名称与 {@code @Scheduled} 的引用指向。<code>@EnableScheduling</code>
 * 的处理发生在 refresh 阶段：如果 {@code @Scheduled(scheduler = ...)} 指向的 bean 不存在或类型不对，refresh 会直接失败——
 * 所以“refresh 成功”本身就是引用可解析的证明。不连接 MySQL/Redis/RabbitMQ，也不启动 {@code BackendApplication}。</p>
 */
class VideoInteractionCleanupSchedulingWiringTests {

    /** 调度组件（{@code @Component}）的默认 bean 名；它**不能**再被线程池占用。 */
    private static final String SCHEDULER_COMPONENT_BEAN = "videoInteractionCleanupScheduler";

    private static final String TASK_SCHEDULER_BEAN =
            VideoInteractionCleanupSchedulingConfig.CLEANUP_TASK_SCHEDULER_BEAN;

    /**
     * 生产里由 Spring Boot 组件扫描装配：{@code @Component VideoInteractionCleanupScheduler} 的 bean 名是
     * 去首字母小写的简单类名 {@code videoInteractionCleanupScheduler}。这里刻意用**类型过滤的组件扫描**
     * （{@code useDefaultFilters = false} + ASSIGNABLE_TYPE）而不是 {@code @Import}：{@code @Import} 会把
     * bean 名注册成全限定类名，那样就复现不出真实的同名冲突了。扫描范围被限制为这一个类，不会拉起
     * 其它服务/ mapper / Redis 组件。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ComponentScan(basePackageClasses = VideoInteractionCleanupScheduler.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = VideoInteractionCleanupScheduler.class))
    @Import(VideoInteractionCleanupSchedulingConfig.class)
    static class WiringConfig {

        @Bean
        VideoInteractionCleanupService cleanupService() {
            VideoInteractionCleanupService service = mock(VideoInteractionCleanupService.class);
            when(service.cleanupOnce())
                    .thenReturn(VideoInteractionCleanupService.RoundResult.skipped());
            return service;
        }

        @Bean
        VideoInteractionProperties videoInteractionProperties() {
            // 保持默认关闭（cleanupEnabled=false）：fixedDelay 任务在 refresh 后可能立即跑一次，
            // 关闭状态让它直接返回，不触碰 mock 服务，也不产生“清理真的执行了”的假象。
            return new VideoInteractionProperties();
        }
    }

    /** 只有调度组件、没有线程池 bean 的隔离上下文（用于证明引用不是被静默忽略的）。 */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ComponentScan(basePackageClasses = VideoInteractionCleanupScheduler.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = VideoInteractionCleanupScheduler.class))
    static class ComponentWithoutTaskSchedulerConfig {

        @Bean
        VideoInteractionCleanupService cleanupService() {
            return mock(VideoInteractionCleanupService.class);
        }

        @Bean
        VideoInteractionProperties videoInteractionProperties() {
            return new VideoInteractionProperties();
        }
    }

    @Test
    void contextStartsWithOverridingDisabledAndKeepsTheTwoBeansDistinct() {
        try (AnnotationConfigApplicationContext context = newContext(WiringConfig.class)) {
            context.refresh();
            // 与 Spring Boot 默认一致：同名 bean 会让这次 refresh 直接失败，而不是悄悄覆盖。
            assertFalse(((DefaultListableBeanFactory) context.getBeanFactory())
                            .isAllowBeanDefinitionOverriding(),
                    "本回归必须关闭 bean 覆盖，否则同名冲突会被静默覆盖掉");

            assertInstanceOf(VideoInteractionCleanupScheduler.class,
                    context.getBean(SCHEDULER_COMPONENT_BEAN),
                    "调度组件必须保留 @Component 的默认 bean 名");

            assertEquals("videoInteractionCleanupTaskScheduler", TASK_SCHEDULER_BEAN,
                    "线程池 bean 名必须带 TaskScheduler 后缀，与组件名区分");
            assertFalse(SCHEDULER_COMPONENT_BEAN.equals(TASK_SCHEDULER_BEAN),
                    "线程池与调度组件不能同名（同名会让应用启动失败）");

            Object taskScheduler = context.getBean(TASK_SCHEDULER_BEAN);
            assertInstanceOf(ThreadPoolTaskScheduler.class, taskScheduler);
            assertFalse(taskScheduler instanceof VideoInteractionCleanupScheduler,
                    "线程池 bean 不是调度组件");

            assertEquals(List.of(TASK_SCHEDULER_BEAN),
                    List.of(context.getBeanNamesForType(ThreadPoolTaskScheduler.class)),
                    "上下文里只应有一个清理专用线程池 bean");
            assertEquals(List.of(SCHEDULER_COMPONENT_BEAN),
                    List.of(context.getBeanNamesForType(VideoInteractionCleanupScheduler.class)),
                    "上下文里只应有一个调度组件 bean");
            assertTrue(context.containsBean(SCHEDULER_COMPONENT_BEAN));
            assertTrue(context.containsBean(TASK_SCHEDULER_BEAN));
        }
    }

    @Test
    void scheduledMethodReferencesTheTaskSchedulerBeanNotTheComponent() throws Exception {
        Method method = VideoInteractionCleanupScheduler.class
                .getMethod("cleanupInteractionTables");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "清理入口必须仍然由 @Scheduled 驱动");

        assertEquals(TASK_SCHEDULER_BEAN, scheduled.scheduler(),
                "@Scheduled 必须引用线程池 bean");
        assertFalse(SCHEDULER_COMPONENT_BEAN.equals(scheduled.scheduler()),
                "引用组件自身会指向错误类型（组件不是 TaskScheduler）");
        assertEquals("${app.video-interaction.cleanup-fixed-delay-ms:60000}",
                scheduled.fixedDelayString());

        try (AnnotationConfigApplicationContext context = newContext(WiringConfig.class)) {
            context.refresh();
            // refresh 成功 = 调度处理器在注册任务时真的解析到了这个 scheduler bean。
            assertTrue(context.containsBean(
                            "org.springframework.context.annotation.internalScheduledAnnotationProcessor"),
                    "@EnableScheduling 必须注册调度处理器");
            assertInstanceOf(ThreadPoolTaskScheduler.class,
                    context.getBean(scheduled.scheduler(), TaskScheduler.class),
                    "@Scheduled 引用的名字必须解析成 TaskScheduler");
        }
    }

    @Test
    void missingTaskSchedulerBeanFailsContextStartupInsteadOfBeingIgnored() {
        try (AnnotationConfigApplicationContext context =
                     newContext(ComponentWithoutTaskSchedulerConfig.class)) {
            Exception failure = assertThrows(Exception.class, context::refresh,
                    "缺少 @Scheduled 引用的线程池 bean 时，refresh 必须失败而不是静默用别的调度器");
            assertTrue(messageChain(failure).contains(TASK_SCHEDULER_BEAN),
                    "@Scheduled 引用被解析时报错必须点名这个 bean；实际异常链："
                            + messageChain(failure));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 只注册、不 refresh：正/负向用例都要自己控制 refresh 发生的位置。 */
    private static AnnotationConfigApplicationContext newContext(Class<?>... components) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setAllowBeanDefinitionOverriding(false);
        context.register(components);
        return context;
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            messages.append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(current.getMessage())
                    .append(" | ");
            current = current.getCause();
        }
        return messages.toString();
    }
}
