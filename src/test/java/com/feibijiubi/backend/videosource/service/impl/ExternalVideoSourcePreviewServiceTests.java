package com.feibijiubi.backend.videosource.service.impl;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsDetailSnapshot;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsJsonAdapter;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsListSnapshot;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsParseException;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsResponseMismatchException;
import com.feibijiubi.backend.videosource.integration.transport.CmsHttpRequestException;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceCatalogClient;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceClientProvider;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceDetailClient;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourcePreviewQuotaService;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourcePreviewService;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceRegistry;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceCatalogVO;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceDetailVO;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceVO;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M3-B：预览服务的纯单测 —— 不启动 Spring、不连数据库/Redis、<b>不解析 DNS、不发任何网络请求</b>。
 *
 * <p>做法：注册表用<b>真实</b>实现（它只是内存快照），上游客户端 seam 与配额 seam 用 Mockito mock，
 * 快照用<b>真实</b>解析器解析合成 JSON（域名只用 {@code example.invalid}）生成。于是被验证的是真实的
 * "准入顺序 → 调用 seam → 脱敏映射"链路，而不是又一层假实现。</p>
 *
 * <p>断言的重点：参数/开关/来源/媒体策略的拒绝都不产生上游请求；配额在发请求之前消耗；
 * permit 在任何分支都释放（成功、上游失败、429、Redis 失败）；上游失败折叠成固定文案的 502 且不带
 * cause/URL；返回的 VO 只有脱敏字段。</p>
 */
class ExternalVideoSourcePreviewServiceTests {

    private static final String API_BASE = "https://api.example.invalid/api.php/provide/vod/";
    private static final String API_ENDPOINT = "api.example.invalid:443";
    private static final String MEDIA_ENDPOINT = "media.example.invalid:443";
    private static final Integer ADMIN_UID = 7;
    private static final String SOURCE_CODE = "demo";

    private static final ExternalVideoUrlPolicy API_POLICY = new ExternalVideoUrlPolicy(
            List.of(new ExternalVideoUrlPolicy.AllowedEndpoint("api.example.invalid", 443)),
            ExternalVideoSourceProperties.MAX_API_BASE_LENGTH);

    private static final ExternalVideoUrlPolicy MEDIA_POLICY = new ExternalVideoUrlPolicy(
            List.of(new ExternalVideoUrlPolicy.AllowedEndpoint("media.example.invalid", 443)),
            AppleCmsJsonAdapter.MAX_PLAY_ADDRESS_LENGTH);

    private final ExternalVideoSourceClientProvider clientProvider =
            mock(ExternalVideoSourceClientProvider.class);
    private final ExternalVideoSourcePreviewQuotaService quotaService =
            mock(ExternalVideoSourcePreviewQuotaService.class);
    private final ExternalVideoSourceCatalogClient catalogClient =
            mock(ExternalVideoSourceCatalogClient.class);
    private final ExternalVideoSourceDetailClient detailClient =
            mock(ExternalVideoSourceDetailClient.class);

    // ---------------------------------------------------------------- 目录：成功路径

    @Test
    void catalogMapsOnlySafeFieldsAndKeepsUpstreamPaginationAsDisplayOnly() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 2, source(true, true)));
        when(clientProvider.createCatalogClient(any())).thenReturn(catalogClient);
        when(catalogClient.fetchPage(2, 21)).thenReturn(listSnapshot());

        ExternalVideoSourceCatalogVO vo = previewService.catalog(ADMIN_UID, SOURCE_CODE, 2, 21);

        assertEquals(SOURCE_CODE, vo.sourceCode());
        assertEquals(2, vo.page());
        assertEquals(3L, vo.pageCount(), "上游自报总页数只作展示，不驱动翻页");
        assertEquals(41L, vo.total());
        assertEquals(2, vo.items().size());
        assertEquals("100001", vo.items().get(0).workIdentity());
        assertEquals("合成作品甲", vo.items().get(0).title());
        assertEquals("21", vo.items().get(0).categoryId());
        assertEquals("合成分类", vo.items().get(0).categoryName());
        assertNull(vo.items().get(1).categoryId(), "上游没给分类就是未知，不填默认值");

        assertEquals(List.of("workIdentity", "title", "categoryId", "categoryName"),
                componentNames(ExternalVideoSourceCatalogVO.Item.class),
                "目录条目只暴露这四个字段");
        assertEquals(List.of("sourceCode", "page", "pageCount", "total", "items"),
                componentNames(ExternalVideoSourceCatalogVO.class));
        for (String secret : List.of("m3u8", "example.invalid", "token=", "line-alpha")) {
            assertFalse(vo.toString().contains(secret), "目录 VO 不得带出地址或线路键：" + secret);
        }
        verify(quotaService).checkAndConsume(ADMIN_UID);
    }

    @Test
    void catalogConsumesQuotaBeforeCreatingOrCallingAnyUpstreamClient() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 2, source(true, true)));
        when(clientProvider.createCatalogClient(any())).thenReturn(catalogClient);
        when(catalogClient.fetchPage(1, null)).thenReturn(listSnapshot());

        previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null);

        InOrder order = inOrder(quotaService, clientProvider, catalogClient);
        order.verify(quotaService).checkAndConsume(ADMIN_UID);
        order.verify(clientProvider).createCatalogClient(any());
        order.verify(catalogClient).fetchPage(1, null);
    }

    // ---------------------------------------------------------------- 目录：拒绝路径

    @Test
    void catalogRejectsInvalidParametersBeforeQuotaOrUpstream() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 2, source(true, true)));

        assertEquals(400, assertThrows(BusinessException.class,
                () -> previewService.catalog(ADMIN_UID, SOURCE_CODE, 0, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> previewService.catalog(ADMIN_UID, SOURCE_CODE, 100_001, null)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, -1)).getCode());

        verify(quotaService, never()).checkAndConsume(any());
        verify(clientProvider, never()).createCatalogClient(any());
    }

    @Test
    void catalogRequiresLoginStateUserId() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 2, source(true, true)));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> previewService.catalog(null, SOURCE_CODE, 1, null));

        assertEquals(401, failure.getCode(), "uid 只能来自登录态，缺失即拒绝");
        verify(quotaService, never()).checkAndConsume(any());
        verify(clientProvider, never()).createCatalogClient(any());
    }

    @Test
    void previewDisabledIs503WithoutAnyUpstreamAccess() {
        ExternalVideoSourcePreviewService previewService = newService(properties(false, 2, source(true, true)));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null));

        assertEquals(503, failure.getCode());
        assertTrue(failure.getMessage().contains(ExternalVideoSourcePreviewService.REASON_PREVIEW_DISABLED));
        verify(quotaService, never()).checkAndConsume(any());
        verify(clientProvider, never()).createCatalogClient(any());
    }

    @Test
    void unknownAndDisabledSourcesShareTheSame404() {
        ExternalVideoSourcePreviewService previewService =
                newService(properties(true, 2, source(false, true)));

        BusinessException unknown = assertThrows(BusinessException.class,
                () -> previewService.catalog(ADMIN_UID, "not-configured", 1, null));
        BusinessException disabled = assertThrows(BusinessException.class,
                () -> previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null));

        assertEquals(404, unknown.getCode());
        assertEquals(404, disabled.getCode());
        assertEquals(unknown.getMessage(), disabled.getMessage(),
                "禁用与未配置必须不可区分，否则可以枚举部署里配了什么");
        assertTrue(unknown.getMessage().contains(ExternalVideoSourcePreviewService.REASON_SOURCE_UNAVAILABLE));
        verify(quotaService, never()).checkAndConsume(any());
        verify(clientProvider, never()).createCatalogClient(any());
    }

    // ---------------------------------------------------------------- 上游失败分类

    @Test
    void upstreamFailuresAreClassifiedAs502WithFixedTextAndNoCause() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 2, source(true, true)));
        when(clientProvider.createCatalogClient(any())).thenReturn(catalogClient);

        Map<RuntimeException, String> cases = new LinkedHashMap<>();
        // 这三个异常类的构造器都是包私有的（固定原因码只由实现自己决定），测试只能 mock 出一个该类型的实例。
        // mock 出来的 Throwable 没有堆栈、没有抑制异常，一旦逃到 JUnit 会把失败报告本身弄坏，
        // 因此注入只能用 doThrow（不执行 mock），且只扔进业务一定会 catch 的那一次调用。
        cases.put(mock(CmsHttpRequestException.class),
                ExternalVideoSourcePreviewService.REASON_UPSTREAM_TRANSPORT_FAILED);
        cases.put(mock(AppleCmsParseException.class),
                ExternalVideoSourcePreviewService.REASON_UPSTREAM_RESPONSE_INVALID);
        cases.put(mock(AppleCmsResponseMismatchException.class),
                ExternalVideoSourcePreviewService.REASON_UPSTREAM_RESPONSE_MISMATCH);

        for (Map.Entry<RuntimeException, String> entry : cases.entrySet()) {
            // 必须用 doThrow 重新打桩：when(...) 会先执行一次 mock，第二轮就会真的抛出上一轮的异常并逃出测试方法。
            doThrow(entry.getKey()).when(catalogClient).fetchPage(1, null);

            BusinessException failure = assertThrows(BusinessException.class,
                    () -> previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null));

            assertEquals(502, failure.getCode());
            assertTrue(failure.getMessage().contains(entry.getValue()), failure.getMessage());
            assertNull(failure.getCause(), "502 不得携带上游 cause");
            for (String secret : List.of("example.invalid", "m3u8", "/api.php")) {
                assertFalse(failure.getMessage().contains(secret), failure.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- 配额与 permit

    @Test
    void quotaRejectionIs429AndSendsNoUpstreamRequest() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 1, source(true, true)));
        doThrow(new BusinessException(429, "外部来源预览请求过于频繁，请稍后再试"))
                .when(quotaService).checkAndConsume(ADMIN_UID);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null));

        assertEquals(429, failure.getCode());
        verify(clientProvider, never()).createCatalogClient(any());
        verify(catalogClient, never()).fetchPage(anyInt(), any());
    }

    @Test
    void redisFailureIsFailClosedAndReleasesThePermit() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 1, source(true, true)));
        doThrow(new RedisOperationException("Redis 操作失败"))
                .when(quotaService).checkAndConsume(ADMIN_UID);

        assertThrows(RedisOperationException.class,
                () -> previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null),
                "Redis 故障必须 fail-closed，不能放行");
        verify(catalogClient, never()).fetchPage(anyInt(), any());

        // permit 若泄漏，maxConcurrent=1 时下面这次会直接 429。
        doNothing().when(quotaService).checkAndConsume(ADMIN_UID);
        when(clientProvider.createCatalogClient(any())).thenReturn(catalogClient);
        when(catalogClient.fetchPage(1, null)).thenReturn(listSnapshot());

        assertEquals(2, previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null).items().size());
    }

    @Test
    void permitIsReleasedOnSuccessAndOnEveryFailureBranch() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 2, source(true, true)));
        when(clientProvider.createCatalogClient(any())).thenReturn(catalogClient);
        when(catalogClient.fetchPage(1, null)).thenReturn(listSnapshot());

        previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null);

        // 同一条桩要从"成功"改成"抛异常"：用 doThrow，别让 when(...) 真的执行一次 mock。
        doThrow(mock(AppleCmsParseException.class)).when(catalogClient).fetchPage(1, null);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThrows(BusinessException.class,
                    () -> previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null));
        }

        doThrow(new BusinessException(429, "超限")).when(quotaService).checkAndConsume(ADMIN_UID);
        assertEquals(429, assertThrows(BusinessException.class,
                () -> previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null)).getCode());

        doNothing().when(quotaService).checkAndConsume(ADMIN_UID);
        // 再从"抛异常"改回"成功"：同样必须用 doReturn，否则 when(...) 会抛出上面那个异常。
        doReturn(listSnapshot()).when(catalogClient).fetchPage(1, null);
        assertEquals(2, previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null).items().size(),
                "4 次上游失败 + 1 次配额拒绝之后仍能拿到 permit，说明每个分支都释放了它");
    }

    @Test
    void concurrentCallOverThePermitIsRejectedWithoutQueueingOrQuota() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 1, source(true, true)));
        AppleCmsListSnapshot snapshot = listSnapshot();
        BusinessException[] nestedFailure = new BusinessException[1];
        when(clientProvider.createCatalogClient(any())).thenReturn(catalogClient);
        when(catalogClient.fetchPage(1, null)).thenAnswer(invocation -> {
            try {
                previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null);
            } catch (BusinessException e) {
                nestedFailure[0] = e;
            }
            return snapshot;
        });

        ExternalVideoSourceCatalogVO vo = previewService.catalog(ADMIN_UID, SOURCE_CODE, 1, null);

        assertEquals(2, vo.items().size());
        assertNotNull(nestedFailure[0], "重入调用必须被并发 permit 立刻拒绝，而不是排队等待");
        assertEquals(429, nestedFailure[0].getCode());
        assertTrue(nestedFailure[0].getMessage()
                .contains(ExternalVideoSourcePreviewService.REASON_CONCURRENCY_LIMIT));
        verify(quotaService, times(1)).checkAndConsume(ADMIN_UID);
        verify(catalogClient, times(1)).fetchPage(1, null);
    }

    // ---------------------------------------------------------------- 详情

    @Test
    void detailWithoutMediaPolicyIs503AndNeverTouchesUpstream() {
        ExternalVideoSourcePreviewService previewService =
                newService(properties(true, 2, source(true, false)));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> previewService.detail(ADMIN_UID, SOURCE_CODE, "100001"));

        assertEquals(503, failure.getCode());
        assertTrue(failure.getMessage().contains(ExternalVideoSourcePreviewService.REASON_MEDIA_POLICY_REQUIRED));
        verify(quotaService, never()).checkAndConsume(any());
        verify(clientProvider, never()).createDetailClient(any());
    }

    @Test
    void detailReturnsSnapshotPositionsAndNeverExposesAddressesOrLineKeys() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 2, source(true, true)));
        when(clientProvider.createDetailClient(any())).thenReturn(detailClient);
        when(detailClient.fetchWork("100001")).thenReturn(detailSnapshot("\"vod_duration\":\"\","));

        ExternalVideoSourceDetailVO vo = previewService.detail(ADMIN_UID, SOURCE_CODE, "100001");

        assertEquals(SOURCE_CODE, vo.sourceCode());
        assertEquals("100001", vo.workIdentity());
        assertEquals("合成作品甲", vo.title());
        assertFalse(vo.hasDurationHint());
        assertEquals(2, vo.lines().size());
        assertEquals(1, vo.lines().get(0).lineIndex(), "线路位置是快照位次，不是线路 ID");
        assertEquals(2, vo.lines().get(0).entries().size());
        assertEquals(1, vo.lines().get(0).entries().get(0).entryIndex());
        assertEquals("第1集", vo.lines().get(0).entries().get(0).label());
        assertEquals(List.of("lineIndex", "entries"), componentNames(ExternalVideoSourceDetailVO.Line.class));
        assertEquals(List.of("entryIndex", "label"),
                componentNames(ExternalVideoSourceDetailVO.Entry.class),
                "条目只暴露快照位置与标签，没有地址、没有 episodeId");
        for (String secret : List.of("media.example.invalid", "m3u8", "token=alpha",
                "line-alpha", "line-beta")) {
            assertFalse(vo.toString().contains(secret), "详情 VO 不得带出地址或线路键：" + secret);
        }
        verify(quotaService).checkAndConsume(ADMIN_UID);
    }

    @Test
    void detailExposesOnlyWhetherADurationHintExists() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 2, source(true, true)));
        when(clientProvider.createDetailClient(any())).thenReturn(detailClient);

        when(detailClient.fetchWork("100001")).thenReturn(detailSnapshot("\"vod_duration\":\"45分钟\","));
        ExternalVideoSourceDetailVO withHint = previewService.detail(ADMIN_UID, SOURCE_CODE, "100001");
        when(detailClient.fetchWork("100002")).thenReturn(detailSnapshot("\"vod_duration\":\"\","));
        ExternalVideoSourceDetailVO withoutHint = previewService.detail(ADMIN_UID, SOURCE_CODE, "100002");

        assertTrue(withHint.hasDurationHint());
        assertFalse(withoutHint.hasDurationHint(), "空文本是未知，不是 0 秒");
        assertFalse(withHint.toString().contains("45分钟"), "只暴露有没有，不暴露原文");
    }

    @Test
    void detailRejectsMalformedWorkIdBeforeAnyQuota() {
        ExternalVideoSourcePreviewService previewService = newService(properties(true, 2, source(true, true)));

        for (String workId : List.of("0", "0123", "abc", "1a2", "9".repeat(192), "")) {
            BusinessException failure = assertThrows(BusinessException.class,
                    () -> previewService.detail(ADMIN_UID, SOURCE_CODE, workId), workId);
            assertEquals(400, failure.getCode(), workId);
        }
        assertEquals(400, assertThrows(BusinessException.class,
                () -> previewService.detail(ADMIN_UID, SOURCE_CODE, null)).getCode());

        verify(quotaService, never()).checkAndConsume(any());
        verify(clientProvider, never()).createDetailClient(any());
    }

    // ---------------------------------------------------------------- 来源摘要

    @Test
    void listSourcesRequiresPreviewEnabled() {
        ExternalVideoSourcePreviewService previewService = newService(properties(false, 2, source(true, true)));

        BusinessException failure = assertThrows(BusinessException.class, previewService::listAvailableSources);

        assertEquals(503, failure.getCode());
        assertTrue(failure.getMessage().contains(ExternalVideoSourcePreviewService.REASON_PREVIEW_DISABLED));
    }

    @Test
    void listSourcesReturnsOnlyCodeAndNameForEnabledSources() {
        ExternalVideoSourceProperties.Source disabled = source(false, true);
        disabled.setCode("second-source");
        ExternalVideoSourcePreviewService previewService =
                newService(properties(true, 2, source(true, true), disabled));

        List<ExternalVideoSourceVO> sources = previewService.listAvailableSources();

        assertEquals(List.of(new ExternalVideoSourceVO(SOURCE_CODE, "示例来源")), sources,
                "只有已启用来源，且不含 base/允许列表");
        assertEquals(List.of("code", "name"), componentNames(ExternalVideoSourceVO.class));
        for (String secret : List.of("example.invalid", "/api.php", "443")) {
            assertFalse(sources.toString().contains(secret), "来源摘要不得带出配置：" + secret);
        }
        verify(quotaService, never()).checkAndConsume(any());
    }

    // ---------------------------------------------------------------- helpers

    private ExternalVideoSourcePreviewService newService(ExternalVideoSourceProperties properties) {
        return new ExternalVideoSourcePreviewServiceImpl(
                new ExternalVideoSourceRegistry(properties), clientProvider, quotaService, properties);
    }

    private static ExternalVideoSourceProperties properties(
            boolean previewEnabled,
            int maxConcurrentRequests,
            ExternalVideoSourceProperties.Source... sources) {
        ExternalVideoSourceProperties properties = new ExternalVideoSourceProperties();
        properties.setPreviewEnabled(previewEnabled);
        properties.setMaxConcurrentRequests(maxConcurrentRequests);
        properties.setSources(new ArrayList<>(Arrays.asList(sources)));
        return properties;
    }

    private static ExternalVideoSourceProperties.Source source(boolean enabled, boolean withMediaEndpoints) {
        ExternalVideoSourceProperties.Source source = new ExternalVideoSourceProperties.Source();
        source.setEnabled(enabled);
        source.setCode(SOURCE_CODE);
        source.setName("示例来源");
        source.setApiBase(API_BASE);
        source.setApiAllowedEndpoints(new LinkedHashSet<>(Set.of(API_ENDPOINT)));
        if (withMediaEndpoints) {
            source.setMediaAllowedEndpoints(new LinkedHashSet<>(Set.of(MEDIA_ENDPOINT)));
        }
        return source;
    }

    /** 合成目录响应：第 2 条没有分类，用来锁定"未知就是 null"。 */
    private static AppleCmsListSnapshot listSnapshot() {
        String body = "{\"code\":1,\"msg\":\"合成\",\"page\":2,\"pagecount\":3,\"limit\":\"20\",\"total\":41,"
                + "\"list\":[{\"vod_id\":100001,\"vod_name\":\"合成作品甲\",\"type_id\":21,"
                + "\"type_name\":\"合成分类\",\"vod_play_url\":\"第1集$https://media.example.invalid/x.m3u8\"},"
                + "{\"vod_id\":\"100002\",\"vod_name\":\"合成作品乙\"}]}";
        return new AppleCmsJsonAdapter(API_POLICY).parseList(body.getBytes(StandardCharsets.UTF_8));
    }

    /** 合成详情响应：两条线路、地址带 token，用来验证地址与线路键不会出现在 VO 里。 */
    private static AppleCmsDetailSnapshot detailSnapshot(String durationField) {
        String body = "{\"code\":1,\"list\":[{\"vod_id\":100001,\"vod_name\":\"合成作品甲\","
                + "\"type_id\":21,\"type_name\":\"合成分类\"," + durationField
                + "\"vod_play_from\":\"line-alpha$$$line-beta\","
                + "\"vod_play_url\":\"第1集$https://media.example.invalid/a/1.m3u8?token=alpha"
                + "#第2集$https://media.example.invalid/a/2.m3u8"
                + "$$$第1集$https://media.example.invalid/b/1.m3u8\"}]}";
        return new AppleCmsJsonAdapter(MEDIA_POLICY).parseDetail(body.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
