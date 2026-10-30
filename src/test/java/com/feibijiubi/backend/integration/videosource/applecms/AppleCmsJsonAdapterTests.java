package com.feibijiubi.backend.integration.videosource.applecms;

import com.feibijiubi.backend.integration.videosource.ExternalVideoUrlPolicy;
import com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.BODY_EMPTY;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.BODY_TOO_LARGE;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.CATEGORY_INVALID;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.CLASS_LIST_INVALID;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.CLASS_LIST_TOO_LONG;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.DEPTH_EXCEEDED;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.DETAIL_ITEM_COUNT_INVALID;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.DUPLICATE_FIELD;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.DUPLICATE_LINE_KEY;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.EMPTY_EPISODE_ADDRESS;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.EMPTY_EPISODE_LABEL;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.EMPTY_LINE_GROUP;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.EPISODE_ADDRESS_INVALID;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.EPISODE_ADDRESS_TOO_LONG;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.LINE_KEY_INVALID;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.LIST_ITEM_NOT_OBJECT;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.LIST_MISSING;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.LIST_NOT_ARRAY;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.LIST_TOO_LONG;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.MALFORMED_JSON;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.NO_PLAY_LINES;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.PAGINATION_INVALID;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.PAGINATION_MISSING;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.PLAY_LINE_COUNT_MISMATCH;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.PLAY_LINES_INVALID;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.PLAY_LINES_MISSING;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.ROOT_NOT_OBJECT;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.RESOURCE_LIMIT_EXCEEDED;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.SNAPSHOT_TEXT_TOO_LONG;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.TITLE_INVALID;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.TOO_MANY_EPISODES;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.TOO_MANY_PLAY_LINES;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.TRAILING_CONTENT;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.UPSTREAM_CODE_INVALID;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.UPSTREAM_CODE_NOT_SUCCESS;
import static com.feibijiubi.backend.integration.videosource.applecms.AppleCmsParseException.FailureCode.WORK_IDENTITY_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2-B：{@link AppleCmsJsonAdapter} 的纯单元测试 —— 不启动 Spring、不联网、不连数据库、不读配置。
 *
 * <p>断言的是本适配器声明的解析契约：成功时的快照内容、失败时的固定原因码、地址保真、以及"没有的字段
 * 就是没有"（稳定剧集 ID、单集时长、可播放结论）。它<b>不</b>证明任何真实站点可访问、地址可播放、
 * 内容已授权，也<b>不</b>证明兼容所有 maccms 版本。</p>
 *
 * <p>刻意不断言"哪个检查先触发"这类内部顺序：凡是报文同时违反多条规则的地方，只断言得到的是本适配器
 * 文档化原因码中的一个确定值。</p>
 */
class AppleCmsJsonAdapterTests {

    private static final ExternalVideoUrlPolicy POLICY = new ExternalVideoUrlPolicy(
            List.of(new ExternalVideoUrlPolicy.AllowedEndpoint("media.example.invalid", 443),
                    new ExternalVideoUrlPolicy.AllowedEndpoint("alt.example.invalid", 443)),
            AppleCmsJsonAdapter.MAX_PLAY_ADDRESS_LENGTH);

    private static final AppleCmsJsonAdapter ADAPTER = new AppleCmsJsonAdapter(POLICY);

    private static final String ONE_ENTRY = "第1集$https://media.example.invalid/a/1.m3u8";

    // ---------------------------------------------------------------- 列表：成功路径

    @Test
    void parsesSyntheticListFixtureAsCatalogEntriesOnly() {
        AppleCmsListSnapshot snapshot = ADAPTER.parseList(fixture("list-two-items.json"));

        assertEquals(2, snapshot.items().size());
        AppleCmsCatalogEntry first = snapshot.items().get(0);
        assertEquals("100001", first.workIdentity());
        assertEquals("合成作品甲", first.title());
        assertEquals("21", first.categoryId());
        assertEquals("合成动画分类", first.categoryName());

        AppleCmsCatalogEntry second = snapshot.items().get(1);
        assertEquals("100002", second.workIdentity(), "字符串形式的 vod_id 与整数形式规范化成同一身份");
        assertEquals("合成作品乙", second.title());
        assertEquals("22", second.categoryId());

        assertEquals(new AppleCmsPagination(1, 1, 100, 2), snapshot.pagination());
    }

    @Test
    void acceptsEmptyListPage() {
        AppleCmsListSnapshot snapshot = ADAPTER.parseList(json(
                "{\"code\":1,\"page\":1,\"pagecount\":0,\"limit\":\"20\",\"total\":0,\"list\":[]}"));

        assertTrue(snapshot.items().isEmpty());
        assertEquals(new AppleCmsPagination(1, 0, 20, 0), snapshot.pagination());
    }

    @Test
    void acceptsPaginationAsIntegerOrCanonicalDecimalString() {
        AppleCmsListSnapshot snapshot = ADAPTER.parseList(json(listBody(
                "\"page\":\"2\",\"pagecount\":3,\"limit\":\"20\",\"total\":\"60\"")));

        assertEquals(new AppleCmsPagination(2, 3, 20, 60), snapshot.pagination());
    }

    @Test
    void toleratesUnknownFieldsIncludingNestedStructures() {
        AppleCmsListSnapshot snapshot = ADAPTER.parseList(json(
                "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":\"20\",\"total\":1,"
                        + "\"vod_content\":\"<p>不执行 HTML</p>\","
                        + "\"extra\":{\"a\":[{\"b\":{\"c\":[1,2,3]}}]},"
                        + "\"list\":[{\"vod_id\":1,\"vod_name\":\"合成作品\",\"unknown\":{\"k\":[true,null]}}]}"));

        assertEquals(1, snapshot.items().size());
        assertEquals("合成作品", snapshot.items().get(0).title());
        assertNull(snapshot.items().get(0).categoryId(), "缺少 type_id 就是未知，不填默认值");
    }

    @Test
    void acceptsExactlyThePageLimitAndRejectsOneMoreItem() {
        AppleCmsListSnapshot atLimit = ADAPTER.parseList(
                json(listWithItems(items(AppleCmsJsonAdapter.MAX_LIST_ITEMS, "{\"vod_id\":1,\"vod_name\":\"甲\"}"))));
        assertEquals(AppleCmsJsonAdapter.MAX_LIST_ITEMS, atLimit.items().size());

        assertEquals(LIST_TOO_LONG, listFailure(
                listWithItems(items(AppleCmsJsonAdapter.MAX_LIST_ITEMS + 1, "{\"vod_id\":1,\"vod_name\":\"甲\"}"))));
    }

    // ---------------------------------------------------------------- 列表：失败路径

    @Test
    void rejectsEmptyAndOversizedBodiesBeforeParsing() {
        AppleCmsParseException nullBody = assertThrows(AppleCmsParseException.class, () -> ADAPTER.parseList(null));
        assertEquals(BODY_EMPTY, nullBody.code());
        assertEquals(BODY_EMPTY, listFailure(new byte[0]));
        assertEquals(BODY_EMPTY, detailFailure(new byte[0]));

        byte[] oversized = new byte[AppleCmsJsonAdapter.MAX_BODY_BYTES + 1];
        Arrays.fill(oversized, (byte) ' ');
        assertEquals(BODY_TOO_LARGE, listFailure(oversized));
        assertEquals(BODY_TOO_LARGE, detailFailure(oversized));
    }

    @Test
    void rejectsNonObjectRootTrailingContentAndMalformedJson() {
        assertEquals(ROOT_NOT_OBJECT, listFailure("[1,2]"));
        assertEquals(ROOT_NOT_OBJECT, listFailure("null"));
        assertEquals(TRAILING_CONTENT, listFailure(
                "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,\"list\":[]}{\"code\":1}"));
        assertEquals(MALFORMED_JSON, listFailure("{\"code\":1,"));
        assertEquals(MALFORMED_JSON, listFailure("{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,\"list\":[}"));
    }

    @Test
    void rejectsDuplicateFieldNamesInParsedAndSkippedObjects() {
        assertEquals(DUPLICATE_FIELD, listFailure(
                "{\"code\":1,\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,\"list\":[]}"));
        assertEquals(DUPLICATE_FIELD, listFailure(
                "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,\"list\":[],"
                        + "\"extra\":{\"a\":1,\"a\":2}}"));
        assertEquals(DUPLICATE_FIELD, listFailure(
                "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,"
                        + "\"list\":[{\"vod_id\":1,\"vod_name\":\"甲\",\"vod_name\":\"乙\"}]}"));
    }

    @Test
    void rejectsUnknownSubtreesThatNestTooDeeply() {
        int nesting = AppleCmsJsonAdapter.MAX_JSON_DEPTH + 4;
        String body = "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,\"list\":[],\"extra\":"
                + "[".repeat(nesting) + "]".repeat(nesting) + "}";

        assertEquals(DEPTH_EXCEEDED, listFailure(body));
    }

    @Test
    void rejectsNumberLiteralsLongerThanTheParserLimit() {
        String hugeNumber = "1".repeat(300);

        assertEquals(RESOURCE_LIMIT_EXCEEDED, listFailure(
                "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,\"list\":[],\"extra\":"
                        + hugeNumber + "}"));
    }

    @Test
    void rejectsUpstreamCodeOtherThanOneOrNonInteger() {
        assertEquals(UPSTREAM_CODE_NOT_SUCCESS, listFailure("{\"code\":0,\"list\":[]}"));
        assertEquals(UPSTREAM_CODE_NOT_SUCCESS, listFailure("{\"code\":\"0\",\"list\":[]}"));
        assertEquals(UPSTREAM_CODE_NOT_SUCCESS, listFailure(
                "{\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,\"list\":[]}"));
        assertEquals(UPSTREAM_CODE_INVALID, listFailure("{\"code\":1.0,\"list\":[]}"));
        assertEquals(UPSTREAM_CODE_INVALID, listFailure("{\"code\":true,\"list\":[]}"));
        assertEquals(UPSTREAM_CODE_INVALID, listFailure("{\"code\":null,\"list\":[]}"));
        assertEquals(UPSTREAM_CODE_INVALID, listFailure("{\"code\":[],\"list\":[]}"));

        AppleCmsListSnapshot stringCode = ADAPTER.parseList(json(
                "{\"code\":\"1\",\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,\"list\":[]}"));
        assertTrue(stringCode.items().isEmpty(), "字符串 \"1\" 与整数 1 等价");
    }

    @Test
    void rejectsListShapeViolations() {
        assertEquals(LIST_MISSING, listFailure("{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0}"));
        assertEquals(LIST_MISSING, listFailure(
                "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,\"list\":null}"));
        assertEquals(LIST_NOT_ARRAY, listFailure(
                "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,\"list\":{}}"));
        assertEquals(LIST_ITEM_NOT_OBJECT, listFailure(
                "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"total\":0,\"list\":[1]}"));
    }

    @Test
    void boundsTheUpstreamClassListWithoutKeepingItsContent() {
        assertEquals(CLASS_LIST_INVALID, listFailure(listWithExtra("\"class\":{}")));

        String oversizedClassList = "\"class\":["
                + items(AppleCmsJsonAdapter.MAX_CLASS_ENTRIES + 1, "{\"type_id\":1,\"type_name\":\"分类\"}") + "]";
        assertEquals(CLASS_LIST_TOO_LONG, listFailure(listWithExtra(oversizedClassList)));

        String atLimitClassList = "\"class\":["
                + items(AppleCmsJsonAdapter.MAX_CLASS_ENTRIES, "{\"type_id\":1,\"type_name\":\"分类\"}") + "]";
        AppleCmsListSnapshot snapshot = ADAPTER.parseList(json(listWithExtra(atLimitClassList)));
        assertTrue(snapshot.items().isEmpty(), "class 只计数，不进入目录条目");
    }

    @Test
    void rejectsMissingPaginationFields() {
        assertEquals(PAGINATION_MISSING, listFailure(
                "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":1,\"list\":[]}"));
        assertEquals(PAGINATION_MISSING, listFailure("{\"code\":1,\"list\":[]}"));
    }

    @Test
    void rejectsPaginationValuesThatAreNotCanonicalNonNegativeIntegers() {
        String[][] cases = {
                {"1.0", "1", "1", "1"},
                {"1", "1", "1", "1.5"},
                {"\"01\"", "1", "1", "1"},
                {"-1", "1", "1", "1"},
                {"\" 1\"", "1", "1", "1"},
                {"\"1e3\"", "1", "1", "1"},
                {"\"0\"", "1", "1", "1"},
                {"1", "1", "\"0\"", "1"},
                {"true", "1", "1", "1"},
                {"null", "1", "1", "1"},
                {"1", "1", "1", "\"99999999999999999999\""},
                {"1", "1", "1", "\"-1\""},
        };
        for (String[] values : cases) {
            String body = "{\"code\":1,\"page\":" + values[0] + ",\"pagecount\":" + values[1]
                    + ",\"limit\":" + values[2] + ",\"total\":" + values[3] + ",\"list\":[]}";
            assertEquals(PAGINATION_INVALID, listFailure(body),
                    "分页字段应拒绝：" + String.join(" | ", values));
        }
    }

    @Test
    void rejectsWorkIdentityThatIsNotACanonicalPositiveDecimal() {
        String[] invalidIdentities = {"0", "-1", "\"01\"", "1.0", "true", "null", "\" 1\"", "\"1 \"", "\"1e3\"", "\"\""};
        for (String identity : invalidIdentities) {
            assertEquals(WORK_IDENTITY_INVALID, listFailure(
                    listWithItems("{\"vod_id\":" + identity + ",\"vod_name\":\"甲\"}")),
                    "作品 ID 应拒绝：" + identity);
        }

        String tooLong = "1".repeat(AppleCmsJsonAdapter.MAX_WORK_IDENTITY_LENGTH + 1);
        assertEquals(WORK_IDENTITY_INVALID, listFailure(
                listWithItems("{\"vod_id\":\"" + tooLong + "\",\"vod_name\":\"甲\"}")));

        String atLimit = "1".repeat(AppleCmsJsonAdapter.MAX_WORK_IDENTITY_LENGTH);
        AppleCmsListSnapshot snapshot = ADAPTER.parseList(
                json(listWithItems("{\"vod_id\":\"" + atLimit + "\",\"vod_name\":\"甲\"}")));
        assertEquals(atLimit, snapshot.items().get(0).workIdentity(), "身份按字符串原样保留，不经 long 转换");
    }

    @Test
    void rejectsOverlongTitleWithoutTruncatingIt() {
        String atLimit = "标".repeat(AppleCmsJsonAdapter.MAX_TITLE_LENGTH);
        AppleCmsListSnapshot snapshot = ADAPTER.parseList(json(
                listWithItems("{\"vod_id\":1,\"vod_name\":\"" + atLimit + "\"}")));
        assertEquals(atLimit, snapshot.items().get(0).title());

        String tooLong = "标".repeat(AppleCmsJsonAdapter.MAX_TITLE_LENGTH + 1);
        assertEquals(SNAPSHOT_TEXT_TOO_LONG, listFailure(
                listWithItems("{\"vod_id\":1,\"vod_name\":\"" + tooLong + "\"}")));

        assertEquals(TITLE_INVALID, listFailure(listWithItems("{\"vod_id\":1}")));
        assertEquals(TITLE_INVALID, listFailure(listWithItems("{\"vod_id\":1,\"vod_name\":\"   \"}")));
        assertEquals(TITLE_INVALID, listFailure(listWithItems("{\"vod_id\":1,\"vod_name\":7}")));
    }

    @Test
    void rejectsNonCanonicalCategoryId() {
        assertEquals(CATEGORY_INVALID, listFailure(
                listWithItems("{\"vod_id\":1,\"vod_name\":\"甲\",\"type_id\":21.5}")));
        assertEquals(CATEGORY_INVALID, listFailure(
                listWithItems("{\"vod_id\":1,\"vod_name\":\"甲\",\"type_id\":\"021\"}")));
        assertEquals(CATEGORY_INVALID, listFailure(
                listWithItems("{\"vod_id\":1,\"vod_name\":\"甲\",\"type_id\":1234567890123456789012}")));
        assertEquals("0", ADAPTER.parseList(json(
                listWithItems("{\"vod_id\":1,\"vod_name\":\"甲\",\"type_id\":0}"))).items().get(0).categoryId(),
                "type_id=0 是合法数值而非缺失；不推断其上游含义");
    }

    // ---------------------------------------------------------------- 详情

    @Test
    void parsesSyntheticDetailFixtureWithTwoLinesOfThreeEntries() {
        AppleCmsDetailSnapshot detail = ADAPTER.parseDetail(fixture("detail-two-lines.json"));

        assertEquals("100001", detail.workIdentity());
        assertEquals("合成作品甲", detail.title());
        assertEquals("21", detail.categoryId());
        assertEquals("合成动画分类", detail.categoryName());
        assertNull(detail.workDurationHint(), "空 vod_duration 是未知，不是 0 秒");

        assertEquals(2, detail.playLines().size());
        AppleCmsPlayLine firstLine = detail.playLines().get(0);
        assertEquals(1, firstLine.snapshotOrdinal());
        assertEquals("synthetic_m3u8", firstLine.lineKey());
        assertEquals(3, firstLine.entries().size());

        AppleCmsLineEntry firstEntry = firstLine.entries().get(0);
        assertEquals(1, firstEntry.snapshotOrdinal());
        assertEquals("第1集", firstEntry.episodeLabel());
        assertEquals("https://media.example.invalid/a/1.m3u8?token=alpha&expires=1900000000", firstEntry.address());
        assertEquals(AppleCmsPlaybackState.UNVERIFIED, firstEntry.playbackState());

        AppleCmsPlayLine secondLine = detail.playLines().get(1);
        assertEquals(2, secondLine.snapshotOrdinal());
        assertEquals("synthetic_hls_alt", secondLine.lineKey());
        assertEquals(3, secondLine.entries().size());
        assertEquals("https://alt.example.invalid/b/3.m3u8?sign=b%2Fc",
                secondLine.entries().get(2).address(), "百分号编码的 query 原样保留");
    }

    @Test
    void splitsEntryOnTheFirstDollarSoTheQueryIsNotRewritten() {
        AppleCmsDetailSnapshot detail = ADAPTER.parseDetail(fixture("detail-two-lines.json"));
        AppleCmsLineEntry third = detail.playLines().get(0).entries().get(2);

        assertEquals("第3集", third.episodeLabel());
        assertEquals("https://media.example.invalid/a/3.m3u8?token=g$amma", third.address(),
                "只按第一个 $ 分割，地址里的 $ 不改写");
    }

    @Test
    void keepsDuplicateEpisodeLabelsAcrossLinesWithoutDedup() {
        AppleCmsDetailSnapshot detail = ADAPTER.parseDetail(fixture("detail-two-lines.json"));

        assertEquals("第1集", detail.playLines().get(0).entries().get(0).episodeLabel());
        assertEquals("第1集", detail.playLines().get(1).entries().get(0).episodeLabel());
        assertEquals(6, detail.playLines().stream().mapToInt(line -> line.entries().size()).sum(),
                "两条线路的同名标签各自保留，不合并、不覆盖");
    }

    @Test
    void keepsWorkDurationOnlyAsBoundedTextHint() {
        assertEquals("45分钟", ADAPTER.parseDetail(
                json(detailBodyWithDuration("\"45分钟\""))).workDurationHint());
        assertEquals("300", ADAPTER.parseDetail(
                json(detailBodyWithDuration("300"))).workDurationHint(),
                "数字也只是作品级文本提示，不转成单集秒数");
        assertNull(ADAPTER.parseDetail(json(detailBodyWithDuration("null"))).workDurationHint());
        assertNull(ADAPTER.parseDetail(json(detailBodyWithDuration("{\"minutes\":45}"))).workDurationHint(),
                "非文本非数字一律未知，不猜、不写 0");
    }

    @Test
    void rejectsDetailResponsesWithoutExactlyOneWork() {
        assertEquals(DETAIL_ITEM_COUNT_INVALID, detailFailure("{\"code\":1,\"list\":[]}"));
        assertEquals(DETAIL_ITEM_COUNT_INVALID, detailFailure("{\"code\":1,\"list\":null}"));
        assertEquals(DETAIL_ITEM_COUNT_INVALID, detailFailure(
                "{\"code\":1,\"list\":[" + workObject("100001") + "," + workObject("100002") + "]}"));
    }

    @Test
    void rejectsDetailResponsesWithoutCodeOrWithDuplicateFields() {
        assertEquals(UPSTREAM_CODE_NOT_SUCCESS, detailFailure("{\"list\":[" + workObject("100001") + "]}"));
        assertEquals(DUPLICATE_FIELD, detailFailure(
                "{\"code\":1,\"code\":1,\"list\":[" + workObject("100001") + "]}"));
        assertEquals(DUPLICATE_FIELD, detailFailure(
                "{\"code\":1,\"list\":[{\"vod_id\":100001,\"vod_name\":\"合成作品甲\","
                        + "\"vod_play_from\":\"k1\",\"vod_play_from\":\"k1\","
                        + "\"vod_play_url\":" + quoted(ONE_ENTRY) + "}]}"));
    }

    @Test
    void rejectsTheWholePageWhenAnyItemIsIncomplete() {
        assertEquals(WORK_IDENTITY_INVALID, listFailure(listWithItems(
                "{\"vod_id\":1,\"vod_name\":\"甲\"},{\"vod_name\":\"乙\"}")),
                "缺 vod_id 的条目让整页失败，不返回跳过坏条目后的部分结果");
        assertEquals(TITLE_INVALID, listFailure(listWithItems(
                "{\"vod_id\":1,\"vod_name\":\"甲\"},{\"vod_id\":2}")));
    }

    @Test
    void rejectsMissingOrNonTextPlayLineFields() {
        assertEquals(PLAY_LINES_MISSING, detailFailure(
                "{\"code\":1,\"list\":[{\"vod_id\":1,\"vod_name\":\"甲\",\"vod_play_from\":\"k1\"}]}"));
        assertEquals(PLAY_LINES_MISSING, detailFailure(
                "{\"code\":1,\"list\":[{\"vod_id\":1,\"vod_name\":\"甲\",\"vod_play_from\":null,"
                        + "\"vod_play_url\":null}]}"));
        assertEquals(PLAY_LINES_INVALID, detailFailure(
                "{\"code\":1,\"list\":[{\"vod_id\":1,\"vod_name\":\"甲\",\"vod_play_from\":[1],"
                        + "\"vod_play_url\":\"x\"}]}"));
        assertEquals(NO_PLAY_LINES, detailFailure(detailBody("", ONE_ENTRY)));
        assertEquals(NO_PLAY_LINES, detailFailure(detailBody("   ", ONE_ENTRY)));
    }

    @Test
    void rejectsPlayLineCountMismatchAndTooManyLines() {
        assertEquals(PLAY_LINE_COUNT_MISMATCH, detailFailure(detailBody("k1$$$k2", ONE_ENTRY)));
        assertEquals(PLAY_LINE_COUNT_MISMATCH, detailFailure(detailBody("k1", ONE_ENTRY + "$$$" + ONE_ENTRY)));
        assertEquals(TOO_MANY_PLAY_LINES, detailFailure(
                detailBody(lineKeys(AppleCmsJsonAdapter.MAX_PLAY_LINES + 1),
                        lineGroups(AppleCmsJsonAdapter.MAX_PLAY_LINES + 1))));
        assertEquals(AppleCmsJsonAdapter.MAX_PLAY_LINES, ADAPTER.parseDetail(json(
                detailBody(lineKeys(AppleCmsJsonAdapter.MAX_PLAY_LINES),
                        lineGroups(AppleCmsJsonAdapter.MAX_PLAY_LINES)))).playLines().size());
    }

    @Test
    void rejectsLineKeyViolations() {
        assertEquals(LINE_KEY_INVALID, detailFailure(detailBody("k1$$$ ", lineGroups(2))));
        assertEquals(LINE_KEY_INVALID, detailFailure(detailBody(" k1", ONE_ENTRY)));
        assertEquals(LINE_KEY_INVALID, detailFailure(
                detailBody("k".repeat(AppleCmsJsonAdapter.MAX_LINE_KEY_LENGTH + 1), ONE_ENTRY)));
        assertEquals(DUPLICATE_LINE_KEY, detailFailure(detailBody("k1$$$k1", lineGroups(2))));
        assertEquals(EMPTY_LINE_GROUP, detailFailure(detailBody("k1$$$k2", ONE_ENTRY + "$$$")));
    }

    @Test
    void rejectsEpisodeSegmentViolations() {
        assertEquals(EMPTY_EPISODE_LABEL, detailFailure(detailBody("k1", ONE_ENTRY + "#")));
        assertEquals(EMPTY_EPISODE_LABEL, detailFailure(detailBody("k1", "$https://media.example.invalid/a/1.m3u8")));
        assertEquals(EMPTY_EPISODE_ADDRESS, detailFailure(detailBody("k1", "第1集")));
        assertEquals(EMPTY_EPISODE_ADDRESS, detailFailure(detailBody("k1", "第1集$")));
        assertEquals(EMPTY_EPISODE_ADDRESS, detailFailure(detailBody("k1", ONE_ENTRY + "#fragment")),
                "'#' 是协议分隔符而不是 URI fragment：地址带 # 会被切开并明确失败");
        assertEquals(EMPTY_LINE_GROUP, detailFailure(detailBody("k1$$$k2", "$$$" + ONE_ENTRY)));
        assertEquals(TOO_MANY_EPISODES, detailFailure(
                detailBody("k1", entries(AppleCmsJsonAdapter.MAX_EPISODES_PER_LINE + 1))));
        assertEquals(AppleCmsJsonAdapter.MAX_EPISODES_PER_LINE, ADAPTER.parseDetail(json(
                detailBody("k1", entries(AppleCmsJsonAdapter.MAX_EPISODES_PER_LINE))))
                .playLines().get(0).entries().size());
    }

    @Test
    void keepsDuplicateEpisodeLabelsWithinOneLineWithoutDedup() {
        AppleCmsDetailSnapshot detail = ADAPTER.parseDetail(json(detailBody("k1", ONE_ENTRY + "#" + ONE_ENTRY)));

        List<AppleCmsLineEntry> entries = detail.playLines().get(0).entries();
        assertEquals(2, entries.size(), "同一线路里的同名集保留为两条独立条目，不合并、不覆盖");
        assertEquals("第1集", entries.get(0).episodeLabel());
        assertEquals("第1集", entries.get(1).episodeLabel());
        assertEquals(1, entries.get(0).snapshotOrdinal());
        assertEquals(2, entries.get(1).snapshotOrdinal());
    }

    @Test
    void rejectsEpisodeSeparatorBombAtTheExactBodyLimit() {
        byte[] bomb = episodeSeparatorBombAtExactBodyLimit();

        assertEquals(AppleCmsJsonAdapter.MAX_BODY_BYTES, bomb.length,
                "轰炸体恰好等于 body 上限：必须由分集上限而不是 body 大小拒绝");
        assertEquals(TOO_MANY_EPISODES, detailFailure(bomb),
                "分隔符先计数后切分：五十万个 '#' 不会被切成五十万段");
    }

    @Test
    void rejectsPlayLineSeparatorBombBeforeAllocatingLines() {
        int lines = 600;
        byte[] bomb = json(detailBody(lineKeys(lines), paddedLineGroups(lines)));

        assertTrue(bomb.length <= AppleCmsJsonAdapter.MAX_BODY_BYTES, "轰炸体不得先被 body 上限拒绝");
        assertTrue(bomb.length > AppleCmsJsonAdapter.MAX_BODY_BYTES - 64 * 1024, "轰炸体应接近 body 上限");
        assertEquals(TOO_MANY_PLAY_LINES, detailFailure(bomb),
                "两侧段数相同（先过段数一致检查），再由线路上限拒绝");
    }

    @Test
    void rejectsAddressesThatTheInjectedPolicyRefusesWithoutLeakingThem() {
        AppleCmsParseException insecure = detailFailureOf(
                detailBody("k1", "第1集$http://media.example.invalid/a/1.m3u8"));
        assertEquals(EPISODE_ADDRESS_INVALID, insecure.code());

        // 响应里出现允许列表之外的域名不会扩充白名单
        AppleCmsParseException foreignHost = detailFailureOf(
                detailBody("k1", "第1集$https://rogue.example.invalid/a/1.m3u8"));
        assertEquals(EPISODE_ADDRESS_INVALID, foreignHost.code());

        String tooLongAddress = "https://media.example.invalid/" + "a".repeat(2100);
        AppleCmsParseException tooLong = detailFailureOf(detailBody("k1", "第1集$" + tooLongAddress));
        assertEquals(EPISODE_ADDRESS_TOO_LONG, tooLong.code());

        for (AppleCmsParseException failure : List.of(insecure, foreignHost, tooLong)) {
            assertNull(failure.getCause(), "异常不得携带 cause");
            assertFalse(failure.getMessage().contains("example.invalid"), "异常文案不得带出地址");
            assertFalse(failure.getMessage().contains("token"), "异常文案不得带出 query 参数");
            assertFalse(failure.getMessage().contains("http://"), "异常文案不得带出 scheme/地址");
        }
    }

    @Test
    void rejectsMalformedAddressThatThePolicyCannotParse() {
        AppleCmsParseException failure = detailFailureOf(
                detailBody("k1", "第1集$https://media.example.invalid/a b/1.m3u8"));

        assertEquals(EPISODE_ADDRESS_INVALID, failure.code());
        assertNull(failure.getCause());
    }

    // ---------------------------------------------------------------- 工具

    private static byte[] fixture(String name) {
        try (InputStream in = AppleCmsJsonAdapterTests.class
                .getResourceAsStream("/videosource/applecms/" + name)) {
            assertNotNull(in, "fixture 缺失：" + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取 fixture 失败：" + name, e);
        }
    }

    private static byte[] json(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static FailureCode listFailure(String body) {
        return listFailure(json(body));
    }

    private static FailureCode listFailure(byte[] body) {
        return assertThrows(AppleCmsParseException.class, () -> ADAPTER.parseList(body)).code();
    }

    private static FailureCode detailFailure(String body) {
        return detailFailure(json(body));
    }

    private static FailureCode detailFailure(byte[] body) {
        return assertThrows(AppleCmsParseException.class, () -> ADAPTER.parseDetail(body)).code();
    }

    private static AppleCmsParseException detailFailureOf(String body) {
        return assertThrows(AppleCmsParseException.class, () -> ADAPTER.parseDetail(json(body)));
    }

    private static String listBody(String paginationFields) {
        return "{\"code\":1," + paginationFields + ",\"list\":[]}";
    }

    private static String listWithItems(String itemsJson) {
        return "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":\"100\",\"total\":1,\"list\":[" + itemsJson + "]}";
    }

    /** 在分页字段之后插入一个额外字段，用于单独验证该字段。 */
    private static String listWithExtra(String extraFieldJson) {
        return "{\"code\":1,\"page\":1,\"pagecount\":1,\"limit\":\"100\",\"total\":0,"
                + extraFieldJson + ",\"list\":[]}";
    }

    private static String items(int count, String itemJson) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(itemJson);
        }
        return builder.toString();
    }

    private static String workObject(String workIdentity) {
        return "{\"vod_id\":" + workIdentity + ",\"vod_name\":\"合成作品\","
                + "\"vod_play_from\":\"k1\",\"vod_play_url\":\"" + ONE_ENTRY + "\"}";
    }

    private static String detailBody(String playFrom, String playUrl) {
        return "{\"code\":1,\"list\":[{\"vod_id\":100001,\"vod_name\":\"合成作品甲\","
                + "\"vod_play_from\":" + quoted(playFrom) + ",\"vod_play_url\":" + quoted(playUrl) + "}]}";
    }

    private static String detailBodyWithDuration(String durationJson) {
        return "{\"code\":1,\"list\":[{\"vod_id\":100001,\"vod_name\":\"合成作品甲\",\"vod_duration\":"
                + durationJson + ",\"vod_play_from\":\"k1\",\"vod_play_url\":" + quoted(ONE_ENTRY) + "}]}";
    }

    /** 仅供无引号/无转义的合成文本使用。 */
    private static String quoted(String raw) {
        return "\"" + raw + "\"";
    }

    private static String lineKeys(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append("$$$");
            }
            builder.append("line").append(i);
        }
        return builder.toString();
    }

    private static String lineGroups(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append("$$$");
            }
            builder.append("第1集$https://media.example.invalid/l/").append(i).append(".m3u8");
        }
        return builder.toString();
    }

    /** 每个线路组都带一个满长度标签，把轰炸体顶到接近 body 上限；线路键各不相同。 */
    private static String paddedLineGroups(int count) {
        String label = "标".repeat(AppleCmsJsonAdapter.MAX_EPISODE_LABEL_LENGTH);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append("$$$");
            }
            builder.append(label).append("$https://media.example.invalid/l/").append(i).append(".m3u8");
        }
        return builder.toString();
    }

    /**
     * 构造字节数<b>恰好</b>等于 {@link AppleCmsJsonAdapter#MAX_BODY_BYTES} 的详情体：{@code vod_play_url}
     * 全是 {@code #}，合法 JSON 且未超大小上限，因此只有分集上限能拦住它。
     */
    private static byte[] episodeSeparatorBombAtExactBodyLimit() {
        String prefix = "{\"code\":1,\"list\":[{\"vod_id\":100001,\"vod_name\":\"合成作品甲\","
                + "\"vod_play_from\":\"k1\",\"vod_play_url\":\"";
        String suffix = "\"}]}";
        int overhead = (prefix + suffix).getBytes(StandardCharsets.UTF_8).length;
        return json(prefix + "#".repeat(AppleCmsJsonAdapter.MAX_BODY_BYTES - overhead) + suffix);
    }

    private static String entries(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                builder.append('#');
            }
            builder.append("第").append(i).append("集$https://media.example.invalid/e/").append(i).append(".m3u8");
        }
        return builder.toString();
    }
}
