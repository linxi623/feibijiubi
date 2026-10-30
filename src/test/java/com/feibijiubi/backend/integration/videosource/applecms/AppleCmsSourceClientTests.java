package com.feibijiubi.backend.integration.videosource.applecms;

import com.feibijiubi.backend.integration.videosource.ExternalVideoUrlPolicy;
import com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode;
import com.feibijiubi.backend.integration.videosource.transport.CmsHttpRequestException;
import com.feibijiubi.backend.integration.videosource.transport.RestrictedCmsHttpClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * E2-D：{@link AppleCmsSourceClient} 的纯单元测试 —— 不启动 Spring、不联网、不连数据库、不读配置。
 *
 * <p>做法是<b>只</b>把已有的受限 HTTP 客户端换成 Mockito mock（它返回的 {@code byte[]} 是"已经取到的 body"），
 * 解析器用<b>真实</b>的 {@link AppleCmsJsonAdapter}，body 用合成 fixture + {@code example.invalid} 允许端点
 * 策略。于是测试覆盖的是真实的"取 body → 解析 → 响应一致性校验"链路，而不是又一层 mock。</p>
 *
 * <p>断言的是本类声明的契约：三处一致性校验的成功与失败、未指定分类时不过滤、空页合法、既有解析/传输异常
 * 原样上抛、失败路径不泄漏响应内容、以及"一次调用只有一次请求"（不重试）。它<b>不</b>证明任何真实站点可
 * 访问、地址可播放、内容已授权，也不证明兼容所有 maccms 版本。</p>
 */
class AppleCmsSourceClientTests {

    private static final ExternalVideoUrlPolicy POLICY = new ExternalVideoUrlPolicy(
            List.of(new ExternalVideoUrlPolicy.AllowedEndpoint("media.example.invalid", 443)),
            AppleCmsJsonAdapter.MAX_PLAY_ADDRESS_LENGTH);

    private static final AppleCmsJsonAdapter ADAPTER = new AppleCmsJsonAdapter(POLICY);

    private static final String ONE_ENTRY = "第1集$https://media.example.invalid/a/1.m3u8";

    private static final String TWO_ENTRIES = ONE_ENTRY + "#第2集$https://media.example.invalid/a/2.m3u8";

    /** 合成敏感标记：代表上游可能塞进标题/query 的凭据或自由文本，用来验证失败路径不把它带出去。 */
    private static final String SECRET_MARKER = "SECRET-MARKER-7f3a";

    // ---------------------------------------------------------------- 构造

    @Test
    void rejectsNullDependenciesAtConstruction() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);

        assertThrows(IllegalArgumentException.class, () -> new AppleCmsSourceClient(null, ADAPTER));
        assertThrows(IllegalArgumentException.class, () -> new AppleCmsSourceClient(httpClient, null));

        verifyNoInteractions(httpClient);
    }

    // ---------------------------------------------------------------- 列表：成功路径

    @Test
    void fetchListReturnsParsedSnapshotWhenPageAndCategoryMatch() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        when(httpClient.fetchList(2, 21)).thenReturn(json(listBody(2,
                item("100001", "合成作品甲", "21"),
                item("100002", "合成作品乙", "21"))));

        AppleCmsListSnapshot snapshot = clientWith(httpClient).fetchList(2, 21);

        assertEquals(2, snapshot.items().size());
        assertEquals("100001", snapshot.items().get(0).workIdentity());
        assertEquals("合成作品甲", snapshot.items().get(0).title(), "标题等快照数据原样保留，不清空业务字段");
        assertEquals("21", snapshot.items().get(0).categoryId());
        assertEquals("合成作品乙", snapshot.items().get(1).title());
        assertEquals(new AppleCmsPagination(2, 3, 20, 60), snapshot.pagination());
        verify(httpClient, times(1)).fetchList(2, 21);
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    void fetchListMatchesCategoryZeroAsACanonicalNumericValue() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        when(httpClient.fetchList(1, 0)).thenReturn(json(listBody(1, item("100001", "合成作品甲", "0"))));

        AppleCmsListSnapshot snapshot = clientWith(httpClient).fetchList(1, 0);

        assertEquals("0", snapshot.items().get(0).categoryId());
        verify(httpClient, times(1)).fetchList(1, 0);
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    void fetchListWithoutCategoryReturnsEveryEntryUnfiltered() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        when(httpClient.fetchList(1, null)).thenReturn(json(listBody(1,
                item("100001", "合成作品甲", "21"),
                item("100002", "合成作品乙", "22"))));

        AppleCmsListSnapshot snapshot = clientWith(httpClient).fetchList(1, null);

        assertEquals(2, snapshot.items().size(), "未指定分类时不做自动过滤，整页原样返回");
        assertEquals("21", snapshot.items().get(0).categoryId());
        assertEquals("22", snapshot.items().get(1).categoryId());
        verify(httpClient, times(1)).fetchList(1, null);
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    void fetchListAcceptsEmptyPageForTheRequestedPage() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        when(httpClient.fetchList(1, 21)).thenReturn(json(emptyListBody(1)));

        AppleCmsListSnapshot snapshot = clientWith(httpClient).fetchList(1, 21);

        assertTrue(snapshot.items().isEmpty(), "空页合法：这是解析器已有的规则，本类不额外收紧");
        assertEquals(new AppleCmsPagination(1, 0, 20, 0), snapshot.pagination());
        verify(httpClient, times(1)).fetchList(1, 21);
        verifyNoMoreInteractions(httpClient);
    }

    // ---------------------------------------------------------------- 列表：响应不一致

    @Test
    void fetchListRejectsResponsePageThatDiffersFromRequestedPage() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        when(httpClient.fetchList(2, null)).thenReturn(json(listBody(1, item("100001", SECRET_MARKER, "21"))));

        AppleCmsResponseMismatchException failure = assertThrows(AppleCmsResponseMismatchException.class,
                () -> clientWith(httpClient).fetchList(2, null));

        assertEquals(AppleCmsResponseMismatchException.FailureCode.RESPONSE_PAGE_MISMATCH, failure.code());
        assertNull(failure.getCause(), "不一致异常不携带 cause");
        assertFalse(failure.getMessage().contains(SECRET_MARKER), "异常文案不含响应里的自由文本");
        assertFalse(failure.toString().contains(SECRET_MARKER));
        verify(httpClient, times(1)).fetchList(2, null);
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    void fetchListRejectsWholePageWhenAnyEntryBelongsToAnotherCategory() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        when(httpClient.fetchList(2, 21)).thenReturn(json(listBody(2,
                item("100001", "合成作品甲", "21"),
                item("100002", SECRET_MARKER, "22"))));

        AppleCmsResponseMismatchException failure = assertThrows(AppleCmsResponseMismatchException.class,
                () -> clientWith(httpClient).fetchList(2, 21));

        // 整页失败：没有"跳过坏条目"的部分结果，调用方拿不到任何条目。
        assertEquals(AppleCmsResponseMismatchException.FailureCode.RESPONSE_CATEGORY_MISMATCH, failure.code());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains(SECRET_MARKER), "整页拒绝也不泄漏条目文本");
        assertFalse(failure.toString().contains(SECRET_MARKER));
        verify(httpClient, times(1)).fetchList(2, 21);
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    void fetchListRejectsEntryWithoutCategoryWhenCategoryRequested() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        when(httpClient.fetchList(1, 21)).thenReturn(json(listBody(1, item("100001", "合成作品甲", null))));

        AppleCmsResponseMismatchException failure = assertThrows(AppleCmsResponseMismatchException.class,
                () -> clientWith(httpClient).fetchList(1, 21));

        assertEquals(AppleCmsResponseMismatchException.FailureCode.RESPONSE_CATEGORY_MISMATCH, failure.code(),
                "缺少分类就无法证明它属于请求的分类");
        assertNull(failure.getCause());
    }

    // ---------------------------------------------------------------- 详情

    @Test
    void fetchDetailReturnsUnverifiedSnapshotWhenWorkIdentityMatches() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        when(httpClient.fetchDetail("100001"))
                .thenReturn(json(detailBody("100001", "合成作品甲", TWO_ENTRIES)));

        AppleCmsDetailSnapshot snapshot = clientWith(httpClient).fetchDetail("100001");

        assertEquals("100001", snapshot.workIdentity());
        assertEquals("合成作品甲", snapshot.title(), "标题等快照数据原样保留");
        assertNull(snapshot.workDurationHint(), "缺少时长就是未知，不合成 0 秒");
        assertEquals(1, snapshot.playLines().size());
        assertEquals(2, snapshot.playLines().get(0).entries().size());
        assertEquals(AppleCmsPlaybackState.UNVERIFIED,
                snapshot.playLines().get(0).entries().get(1).playbackState(),
                "地址仍是未验证候选，本类不提升为可播放");
        verify(httpClient, times(1)).fetchDetail("100001");
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    void fetchDetailRejectsWorkIdentityThatDiffersFromRequestedId() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        when(httpClient.fetchDetail("100001"))
                .thenReturn(json(detailBody("100002", SECRET_MARKER, ONE_ENTRY)));

        AppleCmsResponseMismatchException failure = assertThrows(AppleCmsResponseMismatchException.class,
                () -> clientWith(httpClient).fetchDetail("100001"));

        assertEquals(AppleCmsResponseMismatchException.FailureCode.RESPONSE_IDENTITY_MISMATCH, failure.code(),
                "结构合法但不是本次请求的作品：拒绝绑定");
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains(SECRET_MARKER));
        assertFalse(failure.toString().contains(SECRET_MARKER));
        assertFalse(failure.getMessage().contains("100002"), "错误作品 ID 也不进入异常文案");
        verify(httpClient, times(1)).fetchDetail("100001");
        verifyNoMoreInteractions(httpClient);
    }

    // ---------------------------------------------------------------- 既有异常原样上抛（不重试、不改写）

    @Test
    void fetchListPropagatesTransportFailureWithoutRewrappingOrRetrying() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        // 传输层异常的构造器是 transport 包私有的，这里用 mock 拿到一个该类型的实例；
        // 关键断言是"同一个实例原样上抛"——本类既不捕获也不重新分类。
        CmsHttpRequestException transportFailure = mock(CmsHttpRequestException.class);
        when(httpClient.fetchList(1, null)).thenThrow(transportFailure);

        CmsHttpRequestException thrown = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertSame(transportFailure, thrown);
        verify(httpClient, times(1)).fetchList(1, null);
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    void fetchListPropagatesParseFailureWithoutRewritingTheReasonOrLeakingTheBody() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        // 合法前缀 + 截断的 JSON，并夹带一个敏感标记：解析必然失败，且这份字节不该出现在异常里。
        when(httpClient.fetchList(1, null)).thenReturn(json(
                "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":\"20\",\"total\":1,"
                        + "\"list\":[{\"vod_id\":100001,\"vod_name\":\"" + SECRET_MARKER + "\""));

        AppleCmsParseException failure = assertThrows(AppleCmsParseException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertEquals(FailureCode.MALFORMED_JSON, failure.code(), "解析原因码不被本类改写");
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains(SECRET_MARKER));
        assertFalse(failure.toString().contains(SECRET_MARKER));
        verify(httpClient, times(1)).fetchList(1, null);
        verifyNoMoreInteractions(httpClient);
    }

    @Test
    void fetchDetailPropagatesAddressRejectionWithoutLeakingTheAddress() {
        RestrictedCmsHttpClient httpClient = mock(RestrictedCmsHttpClient.class);
        when(httpClient.fetchDetail("100001")).thenReturn(json(detailBody("100001", "合成作品甲",
                "第1集$https://not-allowed.example.org/a/1.m3u8?token=" + SECRET_MARKER)));

        AppleCmsParseException failure = assertThrows(AppleCmsParseException.class,
                () -> clientWith(httpClient).fetchDetail("100001"));

        assertEquals(FailureCode.EPISODE_ADDRESS_INVALID, failure.code(),
                "未在允许端点列表里的地址由真实解析器拒绝，本类原样上抛");
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("not-allowed.example.org"), "未授权地址不进入异常文案");
        assertFalse(failure.toString().contains(SECRET_MARKER), "地址里的 query 也不进入异常文案");
        verify(httpClient, times(1)).fetchDetail("100001");
        verifyNoMoreInteractions(httpClient);
    }

    // ---------------------------------------------------------------- 工具

    private static AppleCmsSourceClient clientWith(RestrictedCmsHttpClient httpClient) {
        return new AppleCmsSourceClient(httpClient, ADAPTER);
    }

    private static byte[] json(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static String listBody(long page, String... items) {
        return "{\"code\":1,\"page\":" + page + ",\"pagecount\":3,\"limit\":\"20\",\"total\":60,\"list\":["
                + String.join(",", items) + "]}";
    }

    private static String emptyListBody(long page) {
        return "{\"code\":1,\"page\":" + page + ",\"pagecount\":0,\"limit\":\"20\",\"total\":0,\"list\":[]}";
    }

    /** {@code typeId} 为 {@code null} 表示上游没有给 {@code type_id}（分类未知）。 */
    private static String item(String vodId, String title, String typeId) {
        return "{\"vod_id\":" + vodId + ",\"vod_name\":\"" + title + "\""
                + (typeId == null ? "" : ",\"type_id\":" + typeId) + "}";
    }

    private static String detailBody(String vodId, String title, String playUrl) {
        return "{\"code\":1,\"list\":[{\"vod_id\":" + vodId + ",\"vod_name\":\"" + title
                + "\",\"vod_play_from\":\"k1\",\"vod_play_url\":\"" + playUrl + "\"}]}";
    }
}
