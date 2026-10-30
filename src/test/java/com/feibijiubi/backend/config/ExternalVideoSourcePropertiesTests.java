package com.feibijiubi.backend.config;

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
 * M3-A：部署批准配置的<b>纯单测</b>（不启动 Spring、不连数据库/Redis、不发任何网络请求）。
 *
 * <p>覆盖的是"哪些配置会被拒绝、拒绝时文案里有什么/没有什么"，以及默认值。Spring 绑定是否真的能把
 * kebab-case key 绑到这些字段上，由 {@link ExternalVideoSourcePropertiesBindingTests} 单独证明。</p>
 */
class ExternalVideoSourcePropertiesTests {

    /** 保留域名 {@code .invalid}：即使某天有人误把测试配置带上网络，也不会解析到真实主机。 */
    private static final String API_BASE = "https://api.example.invalid/api.php/provide/vod/";
    private static final String API_ENDPOINT = "api.example.invalid:443";
    private static final String MEDIA_ENDPOINT = "media.example.invalid:443";

    private static ExternalVideoSourceProperties.Source source(String code) {
        ExternalVideoSourceProperties.Source source = new ExternalVideoSourceProperties.Source();
        source.setEnabled(true);
        source.setCode(code);
        source.setName("示例来源");
        source.setApiBase(API_BASE);
        source.setApiAllowedEndpoints(new LinkedHashSet<>(Set.of(API_ENDPOINT)));
        source.setMediaAllowedEndpoints(new LinkedHashSet<>(Set.of(MEDIA_ENDPOINT)));
        return source;
    }

    private static ExternalVideoSourceProperties properties(ExternalVideoSourceProperties.Source... sources) {
        ExternalVideoSourceProperties properties = new ExternalVideoSourceProperties();
        // Arrays.asList 允许 null 元素，用来构造"列表里有 null 条目"的场景。
        properties.setSources(new ArrayList<>(Arrays.asList(sources)));
        return properties;
    }

    private static String rejectionMessage(ExternalVideoSourceProperties properties) {
        return assertThrows(IllegalStateException.class, properties::validate).getMessage();
    }

    @Test
    void defaultsArePreviewOffWithNoConfiguredSources() {
        ExternalVideoSourceProperties properties = new ExternalVideoSourceProperties();
        properties.validate();

        assertFalse(properties.isPreviewEnabled(), "预览默认关闭");
        assertFalse(new ExternalVideoSourceProperties.Source().isEnabled());
        assertTrue(properties.getSources().isEmpty(), "默认零来源是合法配置");
        assertEquals(2, properties.getMaxConcurrentRequests());
        assertEquals(30, properties.getPerAdminRequestsPerMinute());
    }

    @Test
    void validSourcePassesAndDisabledSourcesAreStillValidated() {
        ExternalVideoSourceProperties properties = properties(source("apple-cms"));
        properties.validate();

        ExternalVideoSourceProperties.Source disabled = properties.getSources().get(0);
        disabled.setEnabled(false);
        // 禁用只是运行期开关：把 base 改成未批准地址后仍然必须失败。
        disabled.setApiBase("https://other.example.invalid/api/");
        assertTrue(rejectionMessage(properties)
                .contains(ExternalVideoSourceProperties.REASON_API_BASE_NOT_APPROVED));
    }

    @Test
    void codeIsRequiredCaseSensitiveAndNeverTrimmed() {
        for (String invalid : List.of("AppleCms", " apple-cms", "apple-cms ", "apple cms",
                "_apple", "apple.cms", "apple/cms")) {
            assertTrue(rejectionMessage(properties(source(invalid)))
                            .contains(ExternalVideoSourceProperties.REASON_SOURCE_CODE_INVALID),
                    "编码必须按原样匹配 [a-z][a-z0-9_-]{0,63}，被拒绝的取值：" + invalid);
        }
        for (String missing : Arrays.asList(null, "", "   ")) {
            assertTrue(rejectionMessage(properties(source(missing)))
                            .contains(ExternalVideoSourceProperties.REASON_SOURCE_CODE_MISSING),
                    "空白/缺失编码必须单独报 SOURCE_CODE_MISSING");
        }
        // 合法编码：单字符与 64 字符边界都接受。
        properties(source("a")).validate();
        properties(source("a" + "b".repeat(63))).validate();
    }

    @Test
    void duplicateCodeRejected() {
        assertTrue(rejectionMessage(properties(source("apple-cms"), source("apple-cms")))
                .contains(ExternalVideoSourceProperties.REASON_SOURCE_CODE_DUPLICATE));
    }

    @Test
    void nullSourceEntryRejected() {
        ExternalVideoSourceProperties missing = new ExternalVideoSourceProperties();
        missing.setSources(null);
        assertTrue(rejectionMessage(missing).contains(ExternalVideoSourceProperties.REASON_SOURCES_MISSING));
        assertTrue(rejectionMessage(properties(source("apple-cms"), null))
                .contains(ExternalVideoSourceProperties.REASON_SOURCE_MISSING));
    }

    @Test
    void sourceCountIsCappedAtThirtyTwo() {
        ExternalVideoSourceProperties.Source[] exactly32 = new ExternalVideoSourceProperties.Source[32];
        for (int i = 0; i < exactly32.length; i++) {
            exactly32[i] = source("source-" + i);
        }
        properties(exactly32).validate();

        ExternalVideoSourceProperties.Source[] tooMany = new ExternalVideoSourceProperties.Source[33];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = source("source-" + i);
        }
        assertTrue(rejectionMessage(properties(tooMany))
                .contains(ExternalVideoSourceProperties.REASON_SOURCES_TOO_MANY));
    }

    @Test
    void apiBaseMustPassTheApprovedPolicyAndTheBaseShapeRules() {
        ExternalVideoSourceProperties outside = properties(source("apple-cms"));
        outside.getSources().get(0).setApiBase("https://other.example.invalid/api/");
        assertTrue(rejectionMessage(outside).contains(
                ExternalVideoSourceProperties.REASON_API_BASE_NOT_APPROVED + ":ENDPOINT_NOT_ALLOWED"));

        ExternalVideoSourceProperties withQuery = properties(source("apple-cms"));
        withQuery.getSources().get(0).setApiBase(API_BASE + "?ac=list");
        assertTrue(rejectionMessage(withQuery).contains(
                ExternalVideoSourceProperties.REASON_API_BASE_NOT_APPROVED + ":QUERY_PRESENT"));

        ExternalVideoSourceProperties hostOnly = properties(source("apple-cms"));
        hostOnly.getSources().get(0).setApiBase("https://api.example.invalid");
        assertTrue(rejectionMessage(hostOnly).contains(
                ExternalVideoSourceProperties.REASON_API_BASE_NOT_APPROVED + ":PATH_REQUIRED"));

        ExternalVideoSourceProperties plainHttp = properties(source("apple-cms"));
        plainHttp.getSources().get(0).setApiBase("http://api.example.invalid/api/");
        assertTrue(rejectionMessage(plainHttp).contains(
                ExternalVideoSourceProperties.REASON_API_BASE_NOT_APPROVED + ":SCHEME_NOT_HTTPS"));

        ExternalVideoSourceProperties tooLong = properties(source("apple-cms"));
        tooLong.getSources().get(0).setApiBase(API_BASE + "a".repeat(500));
        assertTrue(rejectionMessage(tooLong)
                .contains(ExternalVideoSourceProperties.REASON_API_BASE_TOO_LONG));

        ExternalVideoSourceProperties missing = properties(source("apple-cms"));
        missing.getSources().get(0).setApiBase(" ");
        assertTrue(rejectionMessage(missing)
                .contains(ExternalVideoSourceProperties.REASON_API_BASE_MISSING));
    }

    @Test
    void endpointSyntaxIsStrictHostPort() {
        for (String invalid : List.of("api.example.invalid", "api.example.invalid:",
                "api.example.invalid:0", "api.example.invalid:0443", "api.example.invalid:70000", "api.example.invalid:44a",
                "http://api.example.invalid:443", "api.example.invalid:443:443", ":443")) {
            ExternalVideoSourceProperties properties = properties(source("apple-cms"));
            properties.getSources().get(0).setApiAllowedEndpoints(new LinkedHashSet<>(Set.of(invalid)));
            assertTrue(rejectionMessage(properties)
                            .contains(ExternalVideoSourceProperties.REASON_ENDPOINT_INVALID),
                    "端点必须是精确 host:port，被拒绝的取值：" + invalid);
        }
    }

    @Test
    void allowlistSizeIsCappedAtThirtyTwo() {
        Set<String> exact = new LinkedHashSet<>();
        exact.add("api.example.invalid:443");
        for (int i = 0; i < 31; i++) {
            exact.add("host" + i + ".example.invalid:443");
        }
        ExternalVideoSourceProperties atLimit = properties(source("apple-cms"));
        atLimit.getSources().get(0).setApiAllowedEndpoints(exact);
        atLimit.getSources().get(0).setMediaAllowedEndpoints(exact);
        atLimit.validate();
        Set<String> tooMany = new LinkedHashSet<>();
        for (int i = 0; i < 33; i++) {
            tooMany.add("host" + i + ".example.invalid:443");
        }
        ExternalVideoSourceProperties properties = properties(source("apple-cms"));
        properties.getSources().get(0).setApiAllowedEndpoints(tooMany);
        assertTrue(rejectionMessage(properties)
                .contains(ExternalVideoSourceProperties.REASON_ENDPOINTS_TOO_MANY));
    }

    @Test
    void apiAllowlistIsRequiredButMediaAllowlistMayBeEmpty() {
        ExternalVideoSourceProperties withoutApi = properties(source("apple-cms"));
        withoutApi.getSources().get(0).setApiAllowedEndpoints(null);
        assertTrue(rejectionMessage(withoutApi)
                .contains(ExternalVideoSourceProperties.REASON_API_ALLOWED_ENDPOINTS_MISSING));

        ExternalVideoSourceProperties withoutMedia = properties(source("apple-cms"));
        withoutMedia.getSources().get(0).setMediaAllowedEndpoints(null);
        withoutMedia.validate();

        ExternalVideoSourceProperties emptyMedia = properties(source("apple-cms"));
        emptyMedia.getSources().get(0).setMediaAllowedEndpoints(new LinkedHashSet<>());
        emptyMedia.validate();
    }

    @Test
    void admissionKnobsAreRangeCheckedButNotExecuted() {
        ExternalVideoSourceProperties concurrentTooLow = properties(source("apple-cms"));
        concurrentTooLow.setMaxConcurrentRequests(0);
        assertTrue(rejectionMessage(concurrentTooLow).contains(
                ExternalVideoSourceProperties.REASON_MAX_CONCURRENT_REQUESTS_OUT_OF_RANGE));

        ExternalVideoSourceProperties concurrentTooHigh = properties(source("apple-cms"));
        concurrentTooHigh.setMaxConcurrentRequests(9);
        assertTrue(rejectionMessage(concurrentTooHigh).contains(
                ExternalVideoSourceProperties.REASON_MAX_CONCURRENT_REQUESTS_OUT_OF_RANGE));

        ExternalVideoSourceProperties rateTooLow = properties(source("apple-cms"));
        rateTooLow.setPerAdminRequestsPerMinute(0);
        assertTrue(rejectionMessage(rateTooLow).contains(
                ExternalVideoSourceProperties.REASON_PER_ADMIN_REQUESTS_PER_MINUTE_OUT_OF_RANGE));

        ExternalVideoSourceProperties rateTooHigh = properties(source("apple-cms"));
        rateTooHigh.setPerAdminRequestsPerMinute(121);
        assertTrue(rejectionMessage(rateTooHigh).contains(
                ExternalVideoSourceProperties.REASON_PER_ADMIN_REQUESTS_PER_MINUTE_OUT_OF_RANGE));

        ExternalVideoSourceProperties atBounds = properties(source("apple-cms"));
        atBounds.setMaxConcurrentRequests(8);
        atBounds.setPerAdminRequestsPerMinute(120);
        atBounds.validate();
    }

    @Test
    void rejectionMessagesStayRedacted() {
        ExternalVideoSourceProperties properties = properties(source("apple-cms"));
        ExternalVideoSourceProperties.Source configured = properties.getSources().get(0);
        configured.setName("内部来源名");
        // base 的 host 不在允许列表里 => 报 ENDPOINT_NOT_ALLOWED。
        configured.setApiAllowedEndpoints(new LinkedHashSet<>(Set.of(MEDIA_ENDPOINT)));

        String message = rejectionMessage(properties);

        assertTrue(message.startsWith("app.external-video-source.sources[0].api-base"),
                "文案必须从字段路径开始，便于定位：" + message);
        assertTrue(message.contains(ExternalVideoSourceProperties.REASON_API_BASE_NOT_APPROVED));
        for (String secret : List.of("api.example.invalid", "media.example.invalid",
                "/api.php", "内部来源名", "apple-cms")) {
            assertFalse(message.contains(secret), "文案不能带配置取值：" + secret + " in " + message);
        }
    }
}
