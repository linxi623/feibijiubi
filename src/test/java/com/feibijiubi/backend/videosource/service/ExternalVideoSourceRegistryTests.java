package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3-A：只读注册表的纯单测（不启动 Spring、不连数据库/Redis、<b>不解析 DNS、不发网络请求</b>）。
 *
 * <p>证明三件事：预览关闭时一律拒绝；未知/未启用来源得到同一个不可用码（不泄露其它配置）；构造出的
 * 快照在配置对象被外部修改后仍然不变。</p>
 */
class ExternalVideoSourceRegistryTests {

    private static final String API_BASE = "https://api.example.invalid/api.php/provide/vod/";
    private static final String API_ENDPOINT = "api.example.invalid:443";
    private static final String MEDIA_ENDPOINT = "media.example.invalid:443";

    /** 默认不带媒体允许列表（= 该来源不支持媒体探测）。 */
    private static ExternalVideoSourceProperties.Source source(String code, boolean enabled) {
        ExternalVideoSourceProperties.Source source = new ExternalVideoSourceProperties.Source();
        source.setEnabled(enabled);
        source.setCode(code);
        source.setName("示例来源");
        source.setApiBase(API_BASE);
        source.setApiAllowedEndpoints(new LinkedHashSet<>(Set.of(API_ENDPOINT)));
        return source;
    }

    private static ExternalVideoSourceProperties properties(boolean previewEnabled,
                                                            ExternalVideoSourceProperties.Source... sources) {
        ExternalVideoSourceProperties properties = new ExternalVideoSourceProperties();
        properties.setPreviewEnabled(previewEnabled);
        properties.setSources(new ArrayList<>(Arrays.asList(sources)));
        return properties;
    }

    private static String rejectionMessage(ExternalVideoSourceRegistry registry, String sourceCode) {
        return assertThrows(IllegalStateException.class,
                () -> registry.requirePreviewSource(sourceCode)).getMessage();
    }

    @Test
    void previewDisabledRejectsEveryCode() {
        ExternalVideoSourceRegistry registry = new ExternalVideoSourceRegistry(
                properties(false, source("apple-cms", true)));

        assertFalse(registry.previewEnabled());
        assertEquals(ExternalVideoSourceRegistry.PREVIEW_DISABLED_MESSAGE,
                rejectionMessage(registry, "apple-cms"), "已知来源在关闭状态下也不得可用");
        assertEquals(ExternalVideoSourceRegistry.PREVIEW_DISABLED_MESSAGE,
                rejectionMessage(registry, "not-configured"));
        assertTrue(registry.listAvailableSources().isEmpty(), "关闭时连来源摘要都不返回");
    }

    @Test
    void unknownDisabledNullAndCaseVariantCodesShareOneUnavailableMessage() {
        ExternalVideoSourceRegistry registry = new ExternalVideoSourceRegistry(
                properties(true, source("disabled-one", false), source("live-one", true)));

        assertTrue(registry.previewEnabled());
        assertEquals(ExternalVideoSourceRegistry.SOURCE_UNAVAILABLE_MESSAGE,
                rejectionMessage(registry, "disabled-one"), "禁用与未配置必须同一个码");
        assertEquals(ExternalVideoSourceRegistry.SOURCE_UNAVAILABLE_MESSAGE,
                rejectionMessage(registry, "not-configured"));
        assertEquals(ExternalVideoSourceRegistry.SOURCE_UNAVAILABLE_MESSAGE,
                rejectionMessage(registry, null));
        assertEquals(ExternalVideoSourceRegistry.SOURCE_UNAVAILABLE_MESSAGE,
                rejectionMessage(registry, "LIVE-ONE"), "code 大小写敏感，大小写变体不是同一个来源");
    }

    @Test
    void requirePreviewSourceReturnsTheServerDescriptorWithApprovedPolicies() {
        ExternalVideoSourceRegistry registry = new ExternalVideoSourceRegistry(
                properties(true, source("live-one", true)));

        ExternalVideoSourceDescriptor descriptor = registry.requirePreviewSource("live-one");

        assertEquals("live-one", descriptor.code());
        assertTrue(descriptor.enabled());
        assertEquals("示例来源", descriptor.name());
        assertEquals(API_BASE, descriptor.apiBase(), "apiBase 只给服务端使用");
        assertEquals(Set.of(new ExternalVideoUrlPolicy.AllowedEndpoint("api.example.invalid", 443)),
                descriptor.apiPolicy().allowedEndpoints());
        assertTrue(descriptor.mediaPolicy().isEmpty(),
                "媒体允许列表为空 = 不支持媒体探测，且不得回退到 API 允许列表");

        List<ExternalVideoSourceRegistry.AvailableSource> available = registry.listAvailableSources();
        assertEquals(List.of(new ExternalVideoSourceRegistry.AvailableSource("live-one", "示例来源")),
                available, "对外摘要只有 code 与名称");
    }

    @Test
    void mediaAllowlistBuildsAnIndependentPolicyAndIsNeverMergedWithApiEndpoints() {
        ExternalVideoSourceProperties.Source configured = source("live-one", true);
        configured.getMediaAllowedEndpoints().add(MEDIA_ENDPOINT);
        ExternalVideoSourceRegistry registry = new ExternalVideoSourceRegistry(properties(true, configured));

        ExternalVideoSourceDescriptor descriptor = registry.requirePreviewSource("live-one");

        assertEquals(Set.of(new ExternalVideoUrlPolicy.AllowedEndpoint("api.example.invalid", 443)),
                descriptor.apiPolicy().allowedEndpoints(), "API 允许列表不得被媒体端点污染");
        assertTrue(descriptor.mediaPolicy().isPresent());
        assertEquals(Set.of(new ExternalVideoUrlPolicy.AllowedEndpoint("media.example.invalid", 443)),
                descriptor.mediaPolicy().orElseThrow().allowedEndpoints(),
                "媒体策略只含媒体允许列表，不与 API 允许列表合并");
    }

    @Test
    void snapshotIsNotAffectedByLaterMutationsOfTheConfiguration() {
        ExternalVideoSourceProperties.Source live = source("live-one", true);
        live.getMediaAllowedEndpoints().add(MEDIA_ENDPOINT);
        ExternalVideoSourceProperties properties = properties(true, live);
        ExternalVideoSourceRegistry registry = new ExternalVideoSourceRegistry(properties);

        live.setEnabled(false);
        live.setApiBase("https://other.example.invalid/");
        live.getApiAllowedEndpoints().add("evil.example.invalid:443");
        live.getMediaAllowedEndpoints().clear();
        properties.getSources().add(source("late-one", true));

        ExternalVideoSourceDescriptor descriptor = registry.requirePreviewSource("live-one");
        assertTrue(descriptor.enabled(), "快照不跟着配置对象变");
        assertEquals(API_BASE, descriptor.apiBase());
        assertEquals(Set.of(new ExternalVideoUrlPolicy.AllowedEndpoint("api.example.invalid", 443)),
                descriptor.apiPolicy().allowedEndpoints(),
                "构造后往配置集合里加端点不能影响已批准的允许列表");
        assertFalse(descriptor.apiPolicy().allowedEndpoints()
                .contains(new ExternalVideoUrlPolicy.AllowedEndpoint("evil.example.invalid", 443)));
        assertTrue(descriptor.mediaPolicy().isPresent(),
                "构造后清空配置里的媒体列表不影响已批准快照");

        assertEquals(1, registry.listAvailableSources().size(), "构造后新增的来源不在快照里");
        assertEquals(ExternalVideoSourceRegistry.SOURCE_UNAVAILABLE_MESSAGE,
                rejectionMessage(registry, "late-one"));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.listAvailableSources()
                        .add(new ExternalVideoSourceRegistry.AvailableSource("x", "y")),
                "返回的摘要列表必须不可修改");
    }

    @Test
    void invalidConfigurationFailsAtConstructionWithoutSpring() {
        ExternalVideoSourceProperties properties = properties(true, source("Bad-Code", true));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ExternalVideoSourceRegistry(properties));

        assertTrue(failure.getMessage()
                .contains(ExternalVideoSourceProperties.REASON_SOURCE_CODE_INVALID));
        assertFalse(failure.getMessage().contains("Bad-Code"), "文案不能回显配置取值");
        assertFalse(failure.getMessage().contains("api.example.invalid"));
    }

    @Test
    void descriptorToStringOmitsBaseAndAllowlists() {
        ExternalVideoSourceProperties.Source configured = source("live-one", true);
        configured.getMediaAllowedEndpoints().add(MEDIA_ENDPOINT);
        ExternalVideoSourceRegistry registry = new ExternalVideoSourceRegistry(properties(true, configured));

        String text = registry.requirePreviewSource("live-one").toString();

        assertTrue(text.contains("code=live-one"));
        assertTrue(text.contains("enabled=true"));
        for (String secret : List.of("api.example.invalid", "media.example.invalid", "/api.php", "示例来源")) {
            assertFalse(text.contains(secret), "toString 只能输出 code/enabled，不能带：" + secret);
        }
    }

    @Test
    void playbackEnabledDefaultsOffAndIsIndependentOfPreview() {
        ExternalVideoSourceProperties defaults = properties(false, source("live-one", true));
        assertFalse(defaults.isPlaybackEnabled(), "playback-enabled 默认关闭");
        assertFalse(new ExternalVideoSourceRegistry(defaults).playbackEnabled());

        ExternalVideoSourceProperties playbackOnly = properties(false, source("live-one", true));
        playbackOnly.setPlaybackEnabled(true);
        ExternalVideoSourceRegistry playbackRegistry = new ExternalVideoSourceRegistry(playbackOnly);

        assertTrue(playbackRegistry.playbackEnabled());
        assertFalse(playbackRegistry.previewEnabled(), "播放开关与预览开关独立");
        assertTrue(playbackRegistry.listAvailableSources().isEmpty(),
                "预览关闭时仍不暴露来源摘要");
        assertEquals("live-one", playbackRegistry.requireEnabledSource("live-one").code(),
                "播放只需要来源存在且启用，不受预览开关影响");
    }
}
