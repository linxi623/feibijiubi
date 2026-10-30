package com.feibijiubi.backend.videosource.config;

import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceDescriptor;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-A：配置绑定的轻量上下文测试（真实 Spring 绑定，但<b>不</b>启动 {@code BackendApplication}、
 * 不导入自动配置，因此不会创建 DataSource / Redis / RabbitMQ 连接，也不启动任何调度线程）。
 *
 * <p>它补的是纯单测补不到的一段：kebab-case 的 {@code app.external-video-source.*} 是否真的能绑到
 * 嵌套来源列表、嵌套 {@code Set<String>} 允许列表上，以及非法取值是否在上下文 refresh 阶段就失败
 * （{@code @PostConstruct} 校验真的被触发）。</p>
 */
class ExternalVideoSourcePropertiesBindingTests {

    private static final String API_BASE = "https://api.example.invalid/api.php/provide/vod/";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ExternalVideoSourceProperties.class)
    static class PropertiesOnlyConfiguration {
    }

    /** 只注册这一个 properties bean：没有自动配置、没有基础设施。 */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesOnlyConfiguration.class);

    @Test
    void defaultBindingKeepsPreviewOffAndConfiguresNoSource() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(), failureText(context.getStartupFailure()));

            ExternalVideoSourceProperties properties =
                    context.getBean(ExternalVideoSourceProperties.class);
            assertFalse(properties.isPreviewEnabled());
            assertTrue(properties.getSources().isEmpty());
            assertEquals(2, properties.getMaxConcurrentRequests());
            assertEquals(30, properties.getPerAdminRequestsPerMinute());
            assertEquals(0, context.getBeanNamesForType(ExternalVideoSourceRegistry.class).length,
                    "本片不注册注册表 bean：接线是下一片的事");
            assertEquals(0, context.getBeanNamesForType(DataSource.class).length,
                    "没有自动配置，因此不会创建 DataSource");
        });
    }

    @Test
    void nestedSourceEntriesAndAllowedEndpointSetsAreBound() {
        runner.withPropertyValues(
                        "app.external-video-source.preview-enabled=true",
                        "app.external-video-source.max-concurrent-requests=4",
                        "app.external-video-source.per-admin-requests-per-minute=45",
                        "app.external-video-source.sources[0].enabled=true",
                        "app.external-video-source.sources[0].code=apple-cms",
                        "app.external-video-source.sources[0].name=示例来源",
                        "app.external-video-source.sources[0].api-base=" + API_BASE,
                        "app.external-video-source.sources[0].api-allowed-endpoints[0]=api.example.invalid:443",
                        "app.external-video-source.sources[0].media-allowed-endpoints[0]=media.example.invalid:443")
                .run(context -> {
                    assertNull(context.getStartupFailure(), failureText(context.getStartupFailure()));

                    ExternalVideoSourceProperties properties =
                            context.getBean(ExternalVideoSourceProperties.class);
                    assertTrue(properties.isPreviewEnabled());
                    assertEquals(4, properties.getMaxConcurrentRequests());
                    assertEquals(45, properties.getPerAdminRequestsPerMinute());
                    assertEquals(1, properties.getSources().size());

                    ExternalVideoSourceProperties.Source source = properties.getSources().get(0);
                    assertTrue(source.isEnabled());
                    assertEquals("apple-cms", source.getCode());
                    assertEquals("示例来源", source.getName());
                    assertEquals(API_BASE, source.getApiBase());
                    assertEquals(Set.of("api.example.invalid:443"), source.getApiAllowedEndpoints());
                    assertEquals(Set.of("media.example.invalid:443"), source.getMediaAllowedEndpoints());

                    // 绑定结果必须能直接喂给注册表：证明"配置 → 快照"这一段在真实绑定值上可用。
                    ExternalVideoSourceDescriptor descriptor =
                            new ExternalVideoSourceRegistry(properties).requirePreviewSource("apple-cms");
                    assertEquals(API_BASE, descriptor.apiBase());
                    assertEquals(Set.of(new ExternalVideoUrlPolicy.AllowedEndpoint("api.example.invalid", 443)),
                            descriptor.apiPolicy().allowedEndpoints());
                    assertTrue(descriptor.mediaPolicy().isPresent());
                });
    }

    @Test
    void invalidSourceConfigurationFailsContextRefresh() {
        runner.withPropertyValues(
                        "app.external-video-source.sources[0].code=AppleCms",
                        "app.external-video-source.sources[0].name=示例来源",
                        "app.external-video-source.sources[0].api-base=" + API_BASE,
                        "app.external-video-source.sources[0].api-allowed-endpoints[0]=api.example.invalid:443")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(),
                            "@PostConstruct 校验必须在 refresh 阶段就让非法配置失败");

                    String text = failureText(context.getStartupFailure());
                    assertTrue(text.contains(ExternalVideoSourceProperties.REASON_SOURCE_CODE_INVALID), text);
                    assertFalse(text.contains("api.example.invalid"), "启动失败文案不能带 base/host：" + text);
                });
    }

    /** 逐层拼接异常链的 message：Spring 会把 {@code IllegalStateException} 包成 bean 创建失败。 */
    private static String failureText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            text.append(current.getMessage()).append('\n');
        }
        return text.toString();
    }
}
