package com.feibijiubi.backend.videosource.integration.applecms;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2-B：标准化快照模型自身的约束 —— 不可变、不泄漏原始地址、不暴露"伪造的"剧集身份/时长/可播放结论。
 *
 * <p>纯内存测试：不启动 Spring、不联网、不解析任何 fixture。它证明的是模型<b>没有</b>某些能力
 * （反射层，按精确 record component 名断言）以及 {@code toString} 不会把上游自由文本、线路键与地址
 * 带进日志（字符串层，用每个字段的秘密标记验证），不证明业务链路可用。</p>
 */
class AppleCmsSnapshotModelTests {

    private static final String ADDRESS =
            "https://media.example.invalid/a/1.m3u8?token=synthetic-secret&expires=1900000000";

    @Test
    void listSnapshotCopiesEntriesImmutably() {
        List<AppleCmsCatalogEntry> source = new ArrayList<>();
        source.add(new AppleCmsCatalogEntry("100001", "合成作品甲", "21", "合成动画分类"));
        AppleCmsListSnapshot snapshot = new AppleCmsListSnapshot(source, new AppleCmsPagination(1, 1, 100, 1));
        source.clear();

        assertEquals(1, snapshot.items().size(), "构造后修改来源列表不得影响快照");
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.items().add(new AppleCmsCatalogEntry("100002", "合成作品乙", null, null)));
    }

    @Test
    void detailSnapshotCopiesLinesAndEntriesImmutably() {
        List<AppleCmsLineEntry> entries = new ArrayList<>();
        entries.add(new AppleCmsLineEntry(1, "第1集", ADDRESS, AppleCmsPlaybackState.UNVERIFIED));
        List<AppleCmsPlayLine> lines = new ArrayList<>();
        lines.add(new AppleCmsPlayLine(1, "synthetic_m3u8", entries));
        AppleCmsDetailSnapshot snapshot = new AppleCmsDetailSnapshot("100001", "合成作品甲", "21", "合成动画分类",
                null, lines);
        entries.clear();
        lines.clear();

        assertEquals(1, snapshot.playLines().size(), "构造后修改来源列表不得影响快照");
        assertEquals(1, snapshot.playLines().get(0).entries().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.playLines().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.playLines().get(0).entries().clear());
    }

    @Test
    void lineEntryToStringKeepsOnlyOrdinalAndPlaybackState() {
        AppleCmsLineEntry entry = new AppleCmsLineEntry(1, "第1集", ADDRESS, AppleCmsPlaybackState.UNVERIFIED);
        String rendered = entry.toString();

        assertFalse(rendered.contains("media.example.invalid"), "toString 不得带出地址");
        assertFalse(rendered.contains("token"), "toString 不得带出 query 参数");
        assertFalse(rendered.contains("https://"), "toString 不得带出 scheme");
        assertFalse(rendered.contains("第1集"), "分集标签也是上游原文，同样不得输出");
        assertTrue(rendered.contains("snapshotOrdinal=1"), "序号仍应可见");
        assertTrue(rendered.contains("UNVERIFIED"), "可播放性状态仍应可见");
        assertTrue(rendered.contains("已脱敏"), "脱敏占位符应可见，便于排查时确认是刻意省略");
    }

    @Test
    void everySnapshotToStringOmitsUpstreamTextAndAddresses() {
        // 每个上游自由文本字段都塞一个可识别的秘密标记：任何一个字段被拼进 toString 都会让断言失败。
        String marker = "SECRET-MARKER";
        AppleCmsCatalogEntry catalogEntry = new AppleCmsCatalogEntry(marker + "-identity", marker + "-title",
                marker + "-category-id", marker + "-category-name");
        AppleCmsListSnapshot list = new AppleCmsListSnapshot(List.of(catalogEntry),
                new AppleCmsPagination(1, 1, 100, 1));
        AppleCmsLineEntry entry = new AppleCmsLineEntry(1, marker + "-label",
                ADDRESS + "&" + marker + "-address", AppleCmsPlaybackState.UNVERIFIED);
        AppleCmsPlayLine line = new AppleCmsPlayLine(1, marker + "-line-key", List.of(entry));
        AppleCmsDetailSnapshot detail = new AppleCmsDetailSnapshot(marker + "-work", marker + "-detail-title",
                marker + "-detail-category-id", marker + "-detail-category-name", marker + "-duration",
                List.of(line));

        List<Object> snapshots = List.of(catalogEntry, list, entry, line, detail);
        for (Object snapshot : snapshots) {
            String rendered = snapshot.toString();
            assertFalse(rendered.contains(marker),
                    snapshot.getClass().getSimpleName() + " 的 toString 不得输出任何上游文本");
            assertFalse(rendered.contains("media.example.invalid"),
                    snapshot.getClass().getSimpleName() + " 的 toString 不得带出地址");
        }

        // 含上游文本的四个 record 必须显式显示脱敏占位符（不用截断文本冒充脱敏）。
        List<Object> textBearing = List.of(catalogEntry, entry, line, detail);
        for (Object snapshot : textBearing) {
            assertTrue(snapshot.toString().contains("已脱敏"),
                    snapshot.getClass().getSimpleName() + " 应显式标记脱敏字段");
        }
        assertTrue(list.toString().contains("itemCount=1"), "目录快照只输出条目数");
        assertTrue(detail.toString().contains("playLineCount=1"), "详情快照只输出线路条数");
        assertTrue(line.toString().contains("entryCount=1"), "线路快照只输出条目数");
    }

    @Test
    void paginationRejectsValuesOutsideItsDeclaredRanges() {
        assertThrows(IllegalArgumentException.class, () -> new AppleCmsPagination(0, 0, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> new AppleCmsPagination(1, -1, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> new AppleCmsPagination(1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new AppleCmsPagination(1, 0, 20, -1));

        assertEquals(1, new AppleCmsPagination(1, 0, 20, 0).page(), "空页的合法组合仍应接受");
    }

    @Test
    void playbackStateHasNoPlayableValue() {
        assertArrayEquals(new AppleCmsPlaybackState[]{AppleCmsPlaybackState.UNVERIFIED},
                AppleCmsPlaybackState.values(),
                "当前只有 UNVERIFIED：模型里不存在\"已可播放\"的取值");
    }

    @Test
    void lineEntryExposesNoDurationOrStableEpisodeIdentity() {
        assertNoComponentNamed(AppleCmsLineEntry.class, "duration", "seconds", "identity", "episodeId", "vid");
    }

    @Test
    void catalogEntryExposesNoPlayAddress() {
        assertNoComponentNamed(AppleCmsCatalogEntry.class, "address", "url", "playFrom", "playUrl");
    }

    @Test
    void detailSnapshotExposesNoEpisodeCountOrSingleEpisodeSeconds() {
        // workDurationHint 是作品级文本提示，允许存在；不允许的是集数或单集秒数这类伪精确字段。
        assertNoComponentNamed(AppleCmsDetailSnapshot.class, "episodeCount", "vodTotal", "seconds",
                "durationSeconds");
    }

    /**
     * 按<b>精确的 record component 名</b>断言，而不是子串：{@code contains("vid")} 这类匹配会误伤
     * {@code provider} 之类未来新增的正当字段，也拦不住 {@code episodeKey} 这类等义改名。
     */
    private static void assertNoComponentNamed(Class<?> type, String... forbiddenNames) {
        Set<String> forbidden = new HashSet<>();
        for (String name : forbiddenNames) {
            forbidden.add(name.toLowerCase(Locale.ROOT));
        }
        for (RecordComponent component : type.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            assertFalse(forbidden.contains(name),
                    type.getSimpleName() + " 不应有 record component " + component.getName());
        }
    }
}
