package com.feibijiubi.backend.videosource.config;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.infrastructure.redis.config.RedisScriptConfig;
import com.feibijiubi.backend.videosource.controller.ExternalVideoSourceAdminController;
import com.feibijiubi.backend.videosource.service.impl.AppleCmsExternalVideoSourceClientProvider;
import com.feibijiubi.backend.videosource.service.impl.ExternalVideoSourcePreviewQuotaServiceImpl;
import com.feibijiubi.backend.videosource.service.impl.ExternalVideoSourcePreviewServiceImpl;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceCatalogClient;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceClientProvider;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceDescriptor;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceDetailClient;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourcePreviewQuotaService;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourcePreviewService;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceRegistry;
import com.feibijiubi.backend.infrastructure.redis.RedisUtils;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * M3-B：真实 Spring 绑定的最小上下文测试 —— 只注册 {@link ExternalVideoSourceConfig} 与生产客户端装配类，
 * <b>不</b>加载 {@code BackendApplication}、不导入自动配置、不读 {@code application.yml}，
 * 因此不会创建 DataSource / Redis / RabbitMQ 连接，也不会启动监听器或调度线程。
 *
 * <p>补的是纯 Mockito 补不到的一段：配置项名 → 字段绑定 → {@code @PostConstruct} 校验 → registry bean →
 * 生产客户端装配。并且证明<b>启动期与构造期都不做 DNS 查询</b>：装配出来的客户端指向一个不可解析的
 * {@code example.invalid} 主机，如果构造时就解析，这里会直接失败。</p>
 *
 * <p>第二个上下文（{@code wiringRunner}）把<b>真实</b>预览服务、真实配额服务与真实 controller 一起装配起来，
 * 覆盖“接口 → 实现 → 依赖”这条线：{@code @Qualifier("fixedWindowRateLimitScript")} 必须能按生产 bean 名
 * 解析（所以 Lua 脚本 bean 来自真实 {@link RedisScriptConfig}），且三个 bean 各自唯一。只有 {@link RedisUtils}
 * 是 mock：它让“启动与默认关闭判定一次都没消耗配额”可以断言（{@code verifyNoInteractions}），而不是靠人工阅读。</p>
 */
class ExternalVideoSourceConfigContextTests {

    private static final String API_BASE = "https://api.example.invalid/api.php/provide/vod/";

    @Configuration(proxyBeanMethods = false)
    @Import({ExternalVideoSourceConfig.class, AppleCmsExternalVideoSourceClientProvider.class})
    static class ExternalVideoSourceOnlyConfiguration {
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ExternalVideoSourceOnlyConfiguration.class);

    private final RedisUtils redisUtils = mock(RedisUtils.class);

    private final ApplicationContextRunner wiringRunner = new ApplicationContextRunner()
            .withUserConfiguration(ExternalVideoSourcePreviewWiringConfiguration.class)
            .withBean(RedisUtils.class, () -> redisUtils);

    @Test
    void defaultsKeepPreviewOffWithNoSourcesAndNoInfrastructure() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));

            ExternalVideoSourceProperties properties = context.getBean(ExternalVideoSourceProperties.class);
            assertFalse(properties.isPreviewEnabled(), "preview-enabled 默认关闭");
            assertFalse(properties.isPlaybackEnabled(), "playback-enabled 默认关闭");
            assertTrue(properties.getSources().isEmpty(), "默认零来源 = 零预览能力");
            assertEquals(2, properties.getMaxConcurrentRequests());
            assertEquals(30, properties.getPerAdminRequestsPerMinute());

            ExternalVideoSourceRegistry registry = context.getBean(ExternalVideoSourceRegistry.class);
            assertFalse(registry.previewEnabled());
            assertFalse(registry.playbackEnabled());
            assertTrue(registry.listAvailableSources().isEmpty());
            assertEquals(1, context.getBeanNamesForType(ExternalVideoSourceClientProvider.class).length);

            assertNoInfrastructure(context);
        });
    }

    @Test
    void approvedSourceIsBoundAndBothClientsAreBuiltWithoutResolvingAnything() {
        runner.withPropertyValues(
                        "app.external-video-source.preview-enabled=true",
                        "app.external-video-source.sources[0].enabled=true",
                        "app.external-video-source.sources[0].code=demo",
                        "app.external-video-source.sources[0].name=示例来源",
                        "app.external-video-source.sources[0].api-base=" + API_BASE,
                        "app.external-video-source.sources[0].api-allowed-endpoints[0]=api.example.invalid:443",
                        "app.external-video-source.sources[0].media-allowed-endpoints[0]=media.example.invalid:443")
                .run(context -> {
                    assertNull(context.getStartupFailure(), failureMessage(context));

                    ExternalVideoSourceRegistry registry = context.getBean(ExternalVideoSourceRegistry.class);
                    assertTrue(registry.previewEnabled());
                    assertEquals(1, registry.listAvailableSources().size());
                    ExternalVideoSourceDescriptor descriptor = registry.requirePreviewSource("demo");
                    assertEquals("demo", descriptor.code());
                    assertEquals("示例来源", descriptor.name());

                    ExternalVideoSourceClientProvider provider =
                            context.getBean(ExternalVideoSourceClientProvider.class);
                    // example.invalid 不可解析：构造期若做 DNS 就会在这里失败，因此"不抛异常"就是
                    // "构造期没有解析"的证据。
                    ExternalVideoSourceCatalogClient catalogClient = provider.createCatalogClient(descriptor);
                    ExternalVideoSourceDetailClient detailClient = provider.createDetailClient(descriptor);
                    assertNotNull(catalogClient);
                    assertNotNull(detailClient);

                    assertNoInfrastructure(context);
                });
    }

    @Test
    void detailClientIsRefusedWhenTheSourceHasNoMediaEndpoints() {
        runner.withPropertyValues(
                        "app.external-video-source.preview-enabled=true",
                        "app.external-video-source.sources[0].enabled=true",
                        "app.external-video-source.sources[0].code=demo",
                        "app.external-video-source.sources[0].name=示例来源",
                        "app.external-video-source.sources[0].api-base=" + API_BASE,
                        "app.external-video-source.sources[0].api-allowed-endpoints[0]=api.example.invalid:443")
                .run(context -> {
                    assertNull(context.getStartupFailure(), failureMessage(context));

                    ExternalVideoSourceDescriptor descriptor = context.getBean(ExternalVideoSourceRegistry.class)
                            .requirePreviewSource("demo");
                    assertTrue(descriptor.mediaPolicy().isEmpty());
                    ExternalVideoSourceClientProvider provider =
                            context.getBean(ExternalVideoSourceClientProvider.class);

                    assertNotNull(provider.createCatalogClient(descriptor),
                            "目录预览不需要媒体授权");
                    assertThrows(IllegalArgumentException.class,
                            () -> provider.createDetailClient(descriptor),
                            "没有媒体端点时必须拒绝构造详情客户端，绝不回退到 API 允许列表");
                });
    }

    @Test
    void previewOffRejectsConfiguredSourcesThroughTheRegistry() {
        runner.withPropertyValues(
                        "app.external-video-source.preview-enabled=false",
                        "app.external-video-source.sources[0].enabled=true",
                        "app.external-video-source.sources[0].code=demo",
                        "app.external-video-source.sources[0].name=示例来源",
                        "app.external-video-source.sources[0].api-base=" + API_BASE,
                        "app.external-video-source.sources[0].api-allowed-endpoints[0]=api.example.invalid:443")
                .run(context -> {
                    assertNull(context.getStartupFailure(), failureMessage(context));

                    ExternalVideoSourceRegistry registry = context.getBean(ExternalVideoSourceRegistry.class);
                    assertFalse(registry.previewEnabled());
                    IllegalStateException failure = assertThrows(IllegalStateException.class,
                            () -> registry.requirePreviewSource("demo"));
                    assertEquals(ExternalVideoSourceRegistry.PREVIEW_DISABLED_MESSAGE, failure.getMessage());
                    assertTrue(registry.listAvailableSources().isEmpty());
                });
    }

    @Test
    void invalidSourceConfigurationFailsStartupWithFieldPathOnly() {
        runner.withPropertyValues(
                        "app.external-video-source.preview-enabled=true",
                        "app.external-video-source.sources[0].code=demo",
                        "app.external-video-source.sources[0].name=示例来源",
                        "app.external-video-source.sources[0].api-allowed-endpoints[0]=api.example.invalid:443")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "缺少 api-base 必须在启动期失败");
                    String messages = failureMessage(context);
                    assertTrue(messages.contains("app.external-video-source.sources[0].api-base"), messages);
                    assertTrue(messages.contains(ExternalVideoSourceProperties.REASON_API_BASE_MISSING), messages);
                    assertFalse(messages.contains("示例来源"), "文案不得回显配置取值");
                });
    }

    // ------------------------------------------------------------ 真实装配（服务 + 配额 + controller）

    /**
     * 真实装配：配置 + 生产客户端装配 + 真实预览服务 + 真实配额服务 + 真实 controller。
     *
     * <p>只有 {@link RedisUtils} 是 mock（本片不连 Redis，脚本执行语义已由
     * {@code ExternalVideoSourcePreviewQuotaServiceImplTests} 覆盖）。Lua 脚本 bean 来自<b>真实</b>
     * {@link RedisScriptConfig}，因此 {@code @Qualifier("fixedWindowRateLimitScript")} 是按生产 bean 名解析的，
     * 不是测试里另造一个同名壳。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @Import({
            ExternalVideoSourceConfig.class,
            AppleCmsExternalVideoSourceClientProvider.class,
            RedisScriptConfig.class,
            ExternalVideoSourcePreviewServiceImpl.class,
            ExternalVideoSourcePreviewQuotaServiceImpl.class,
            ExternalVideoSourceAdminController.class
    })
    static class ExternalVideoSourcePreviewWiringConfiguration {
    }

    /** 与上面相同，但故意<b>不</b>导入 {@link RedisScriptConfig}，只提供一个不同名字的脚本 bean。 */
    @Configuration(proxyBeanMethods = false)
    @Import({
            ExternalVideoSourceConfig.class,
            AppleCmsExternalVideoSourceClientProvider.class,
            ExternalVideoSourcePreviewServiceImpl.class,
            ExternalVideoSourcePreviewQuotaServiceImpl.class,
            ExternalVideoSourceAdminController.class
    })
    static class ExternalVideoSourcePreviewWiringWithoutNamedScriptConfiguration {

        /** 同名类型（{@code DefaultRedisScript<Long>}）但不同名字：按类型兜底就会把它当成配额脚本。 */
        @Bean
        DefaultRedisScript<Long> unrelatedLongScript() {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setLocation(new ClassPathResource("lua/fixed-window-rate-limit.lua"));
            script.setResultType(Long.class);
            return script;
        }
    }

    @Test
    void previewServiceQuotaAndControllerAreWiredOnceAndStartupConsumesNoQuota() {
        wiringRunner.run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));

            assertSingleBean(context, ExternalVideoSourcePreviewService.class,
                    ExternalVideoSourcePreviewServiceImpl.class);
            assertSingleBean(context, ExternalVideoSourcePreviewQuotaService.class,
                    ExternalVideoSourcePreviewQuotaServiceImpl.class);
            assertSingleBean(context, ExternalVideoSourceAdminController.class,
                    ExternalVideoSourceAdminController.class);

            // 默认关闭：真实服务直接给 503，这一步不碰 Redis。
            ExternalVideoSourcePreviewService previewService =
                    context.getBean(ExternalVideoSourcePreviewService.class);
            BusinessException failure = assertThrows(BusinessException.class,
                    previewService::listAvailableSources);
            assertEquals(503, failure.getCode());
            assertTrue(failure.getMessage()
                    .contains(ExternalVideoSourcePreviewService.REASON_PREVIEW_DISABLED));

            // 拒绝路径也不消耗配额：总开关关闭时 catalog 在配额之前就结束。
            assertEquals(503, assertThrows(BusinessException.class,
                    () -> previewService.catalog(7, "demo", 1, null)).getCode());

            // 装配 + 启动 + 默认关闭判定这条路径一次都没有消耗配额（没有任何 Redis 调用）。
            verifyNoInteractions(redisUtils);
            assertNoInfrastructure(context);
        });
    }

    @Test
    void missingNamedScriptBeanFailsStartupInsteadOfFallingBackToAnotherScript() {
        new ApplicationContextRunner()
                .withUserConfiguration(ExternalVideoSourcePreviewWiringWithoutNamedScriptConfiguration.class)
                .withBean(RedisUtils.class, () -> redisUtils)
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "缺少 fixedWindowRateLimitScript 时必须装配失败，不能按类型兜底");
                    String messages = failureMessage(context);
                    assertTrue(messages.contains("ExternalVideoSourcePreviewQuotaServiceImpl"), messages);
                    assertTrue(messages.contains("fixedWindowRateLimitScript"), messages);
                });
    }

    // ------------------------------------------------------------ helpers

    private static void assertSingleBean(AssertableApplicationContext context,
                                         Class<?> type,
                                         Class<?> expectedImplementation) {
        String[] names = context.getBeanNamesForType(type);
        assertEquals(1, names.length, type.getSimpleName() + " 必须唯一");
        assertInstanceOf(expectedImplementation, context.getBean(type),
                type.getSimpleName() + " 必须是生产实现");
    }

    private static void assertNoInfrastructure(AssertableApplicationContext context) {
        assertEquals(0, context.getBeanNamesForType(DataSource.class).length, "不得创建 DataSource");
        assertEquals(0, context.getBeanNamesForType(JdbcTemplate.class).length, "不得创建 JdbcTemplate");
        assertEquals(0, context.getBeanNamesForType(RedisConnectionFactory.class).length,
                "不得创建 Redis 连接工厂");
        assertEquals(0, context.getBeanNamesForType(ConnectionFactory.class).length,
                "不得创建 RabbitMQ 连接工厂");
        assertEquals(0, context.getBeanNamesForType(RabbitListenerContainerFactory.class).length,
                "不得创建监听容器（也就不会消费消息）");
    }

    private static String failureMessage(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        if (failure == null) {
            return "";
        }
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(current.getMessage())
                    .append('\n');
        }
        return messages.toString();
    }
}
