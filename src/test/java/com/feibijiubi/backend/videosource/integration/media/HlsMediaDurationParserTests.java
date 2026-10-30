package com.feibijiubi.backend.videosource.integration.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1：{@link HlsMediaDurationParser} 的离线用例。全部样例都是内联合成清单，使用 {@code example.invalid}
 * 保留域名，不下载、不联网、不读配置、不碰数据库。
 */
class HlsMediaDurationParserTests {

    private static final String TWO_SEGMENTS = "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXTINF:0.1,\n"
            + "seg-1.ts\n"
            + "#EXTINF:0.2,第二段\n"
            + "seg-2.ts\n"
            + "#EXT-X-ENDLIST\n";

    @Test
    void twoSegmentsSumExactlyWithDecimalArithmetic() {
        HlsMediaDurationCandidate candidate = parse(TWO_SEGMENTS);

        assertEquals(new BigDecimal("0.3"), candidate.durationSeconds());
        assertEquals(2, candidate.segmentCount());
        assertEquals(64, candidate.manifestSha256().length());
        assertTrue(candidate.manifestSha256().matches("[0-9a-f]{64}"));
        // 不是二进制浮点逐项累加的结果。
        assertNotEquals(0.1d + 0.2d, candidate.durationSeconds().doubleValue());
    }

    @Test
    void crlfIsAcceptedAndFingerprintCoversLineEndings() {
        HlsMediaDurationCandidate lf = parse(TWO_SEGMENTS);
        HlsMediaDurationCandidate crlf = parse(TWO_SEGMENTS.replace("\n", "\r\n"));

        assertEquals(lf.durationSeconds(), crlf.durationSeconds());
        assertEquals(lf.segmentCount(), crlf.segmentCount());
        assertNotEquals(lf.manifestSha256(), crlf.manifestSha256());
    }

    @Test
    void isolatedCarriageReturnIsRejected() {
        String manifest = "#EXTM3U\r#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n";

        assertEquals(HlsManifestParseException.FailureCode.LINE_ENDING_INVALID, rejectionCode(manifest));
    }

    @Test
    void utf8BomIsRejected() {
        String manifest = "\uFEFF" + TWO_SEGMENTS;

        assertEquals(HlsManifestParseException.FailureCode.BOM_UNSUPPORTED, rejectionCode(manifest));
    }

    @Test
    void malformedUtf8IsRejected() {
        byte[] head = "#EXTM3U\n#EXTINF:1,\nseg".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = new byte[head.length + 2];
        System.arraycopy(head, 0, manifest, 0, head.length);
        manifest[head.length] = (byte) 0xC3;
        manifest[head.length + 1] = (byte) 0x28;

        assertEquals(HlsManifestParseException.FailureCode.MANIFEST_NOT_UTF8, rejectionCode(manifest));
    }

    @Test
    void nulAndControlCharactersAreRejected() {
        assertEquals(HlsManifestParseException.FailureCode.CONTROL_CHARACTER_UNSUPPORTED,
                rejectionCode("#EXTM3U\n#EXTINF:1,\nseg\u0000.ts\n#EXT-X-ENDLIST\n"));
        assertEquals(HlsManifestParseException.FailureCode.CONTROL_CHARACTER_UNSUPPORTED,
                rejectionCode("#EXTM3U\n#EXTINF:1,\nseg\u0001.ts\n#EXT-X-ENDLIST\n"));
        assertEquals(HlsManifestParseException.FailureCode.CONTROL_CHARACTER_UNSUPPORTED,
                rejectionCode("#EXTM3U\n#EXTINF:1,\nseg\u007f.ts\n#EXT-X-ENDLIST\n"));
    }

    @Test
    void tabsInsideLinesAreAllowedAndStructureLinesAreTrimmed() {
        String manifest = "#EXTM3U  \n"
                + "\t#EXT-X-VERSION:3\n"
                + "#EXTINF:1.5,\t标题\t带制表符\n"
                + "seg-1.ts\n"
                + "#EXT-X-ENDLIST\t\n";

        HlsMediaDurationCandidate candidate = parse(manifest);

        assertEquals(new BigDecimal("1.5"), candidate.durationSeconds());
        assertEquals(1, candidate.segmentCount());
    }

    @Test
    void nullAndEmptyInputAreRejected() {
        assertEquals(HlsManifestParseException.FailureCode.MANIFEST_EMPTY, rejectionCode((byte[]) null));
        assertEquals(HlsManifestParseException.FailureCode.MANIFEST_EMPTY, rejectionCode(new byte[0]));
    }

    @Test
    void exactly512KibIsAcceptedAndOneByteMoreIsRejected() {
        byte[] exact = manifestWithExactSize(HlsMediaDurationParser.MAX_MANIFEST_BYTES);
        byte[] oversized = manifestWithExactSize(HlsMediaDurationParser.MAX_MANIFEST_BYTES + 1);

        assertEquals(HlsMediaDurationParser.MAX_MANIFEST_BYTES, exact.length);
        assertEquals(HlsMediaDurationParser.MAX_MANIFEST_BYTES + 1, oversized.length);
        assertEquals(new BigDecimal("1"), HlsMediaDurationParser.parse(exact).durationSeconds());
        assertEquals(HlsManifestParseException.FailureCode.MANIFEST_TOO_LARGE, rejectionCode(oversized));
    }

    @Test
    void exactLineLimitIsAcceptedAndOneMoreIsRejected() {
        String exact = manifestWithExactLineCount(HlsMediaDurationParser.MAX_LINE_COUNT);

        assertEquals(HlsMediaDurationParser.MAX_LINE_COUNT, countLines(exact));
        assertEquals(1, parse(exact).segmentCount());
        assertEquals(HlsManifestParseException.FailureCode.TOO_MANY_LINES,
                rejectionCode(manifestWithExactLineCount(HlsMediaDurationParser.MAX_LINE_COUNT + 1)));
    }

    @Test
    void lineLengthBoundaryIsExact() {
        assertEquals(1, parse(manifestWithCommentLineOfLength(HlsMediaDurationParser.MAX_LINE_LENGTH))
                .segmentCount());
        assertEquals(HlsManifestParseException.FailureCode.LINE_TOO_LONG,
                rejectionCode(manifestWithCommentLineOfLength(HlsMediaDurationParser.MAX_LINE_LENGTH + 1)));
    }

    @Test
    void exactSegmentLimitIsAcceptedAndOneMoreIsRejected() {
        assertEquals(10000, HlsMediaDurationParser.MAX_SEGMENT_COUNT);
        assertEquals(10000, parse(manifestWithSegmentCount(10000)).segmentCount());
        assertEquals(new BigDecimal("10000"), parse(manifestWithSegmentCount(10000)).durationSeconds());
        assertEquals(HlsManifestParseException.FailureCode.SEGMENT_COUNT_EXCEEDED,
                rejectionCode(manifestWithSegmentCount(10001)));
    }

    @Test
    void crlfTerminatorsDoNotConsumeLineLengthBudget() {
        assertEquals(1, parse(manifestWithCommentLineOfLength(HlsMediaDurationParser.MAX_LINE_LENGTH)
                .replace("\n", "\r\n")).segmentCount());
        assertEquals(HlsManifestParseException.FailureCode.LINE_TOO_LONG,
                rejectionCode(manifestWithCommentLineOfLength(HlsMediaDurationParser.MAX_LINE_LENGTH + 1)
                        .replace("\n", "\r\n")));
    }

    @Test
    void durationTokenLengthAndScaleBoundariesAreExact() {
        String longest = "1234567890123456789012.123456789";
        assertEquals(32, longest.length());
        assertEquals(new BigDecimal(longest), parse(singleSegmentManifest(longest)).durationSeconds());
        assertTrue(Double.isFinite(parse(singleSegmentManifest(longest)).durationSeconds().doubleValue()));

        assertEquals(HlsManifestParseException.FailureCode.EXTINF_DURATION_INVALID,
                rejectionCode(singleSegmentManifest(longest + "0")));
        assertEquals(1, parse(singleSegmentManifest("1.123456789")).segmentCount());
        assertEquals(HlsManifestParseException.FailureCode.EXTINF_DURATION_INVALID,
                rejectionCode(singleSegmentManifest("1.1234567890")));
    }

    @Test
    void nonPositiveScientificAndMalformedDurationsAreRejected() {
        for (String token : List.of("0", "0.000", "-1.5", "1e3", "1.", ".5", " 1.0", "1.0 ", "+1", "1..0", "0x10")) {
            assertEquals(HlsManifestParseException.FailureCode.EXTINF_DURATION_INVALID,
                    rejectionCode(singleSegmentManifest(token)), token);
        }
    }

    @Test
    void extinfWithoutCommaIsRejected() {
        assertEquals(HlsManifestParseException.FailureCode.EXTINF_MALFORMED,
                rejectionCode("#EXTM3U\n#EXTINF:1.0\nseg.ts\n#EXT-X-ENDLIST\n"));
        assertEquals(HlsManifestParseException.FailureCode.EXTINF_MALFORMED,
                rejectionCode("#EXTM3U\n#EXTINF:,标题\nseg.ts\n#EXT-X-ENDLIST\n"));
        assertEquals(HlsManifestParseException.FailureCode.EXTINF_MALFORMED,
                rejectionCode("#EXTM3U\n#EXTINF\nseg.ts\n#EXT-X-ENDLIST\n"));
    }

    @Test
    void secondExtinfBeforeSegmentLineIsRejected() {
        assertEquals(HlsManifestParseException.FailureCode.EXTINF_WITHOUT_SEGMENT,
                rejectionCode("#EXTM3U\n#EXTINF:1,\n#EXTINF:2,\nseg.ts\n#EXT-X-ENDLIST\n"));
    }

    @Test
    void hangingExtinfIsRejected() {
        assertEquals(HlsManifestParseException.FailureCode.EXTINF_WITHOUT_SEGMENT,
                rejectionCode("#EXTM3U\n#EXTINF:1,\n"));
        assertEquals(HlsManifestParseException.FailureCode.EXTINF_WITHOUT_SEGMENT,
                rejectionCode("#EXTM3U\n#EXTINF:1,\n#EXT-X-ENDLIST\n"));
    }

    @Test
    void orphanSegmentLineIsRejected() {
        assertEquals(HlsManifestParseException.FailureCode.SEGMENT_URI_ORPHAN,
                rejectionCode("#EXTM3U\nseg.ts\n#EXTINF:1,\nseg-2.ts\n#EXT-X-ENDLIST\n"));
    }

    @Test
    void liveManifestWithoutEndlistIsRejected() {
        assertEquals(HlsManifestParseException.FailureCode.ENDLIST_MISSING,
                rejectionCode("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nseg.ts\n"));
        assertEquals(HlsManifestParseException.FailureCode.ENDLIST_MISSING,
                rejectionCode("#EXTM3U\n"));
    }

    @Test
    void manifestWithoutCompleteSegmentIsRejected() {
        assertEquals(HlsManifestParseException.FailureCode.NO_COMPLETE_SEGMENT,
                rejectionCode("#EXTM3U\n#EXT-X-ENDLIST\n"));
    }

    @Test
    void contentAfterEndlistIsRejectedButBlankLinesAreAllowed() {
        assertEquals(1, parse("#EXTM3U\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n\n\n").segmentCount());
        assertEquals(HlsManifestParseException.FailureCode.CONTENT_AFTER_ENDLIST,
                rejectionCode("#EXTM3U\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\nseg-2.ts\n"));
        assertEquals(HlsManifestParseException.FailureCode.CONTENT_AFTER_ENDLIST,
                rejectionCode("#EXTM3U\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n#注释\n"));
        assertEquals(HlsManifestParseException.FailureCode.CONTENT_AFTER_ENDLIST,
                rejectionCode("#EXTM3U\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n#EXTINF:1,\n"));
    }

    @Test
    void duplicateEndlistIsRejected() {
        assertEquals(HlsManifestParseException.FailureCode.DUPLICATE_ENDLIST,
                rejectionCode("#EXTM3U\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n#EXT-X-ENDLIST\n"));
    }

    @Test
    void headerRulesAreEnforced() {
        assertEquals(HlsManifestParseException.FailureCode.HEADER_MISSING,
                rejectionCode("#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n"));
        assertEquals(HlsManifestParseException.FailureCode.HEADER_MISSING,
                rejectionCode("\n#EXTM3U\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n"));
        assertEquals(HlsManifestParseException.FailureCode.DUPLICATE_HEADER,
                rejectionCode("#EXTM3U\n#EXTINF:1,\nseg.ts\n#EXTM3U\n#EXT-X-ENDLIST\n"));
    }

    @Test
    void masterPlaylistTagsAreRejectedInsteadOfFollowed() {
        for (String tag : List.of(
                "#EXT-X-STREAM-INF:BANDWIDTH=1280000\nmedia.m3u8\n",
                "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=86000\niframe.m3u8\n",
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\"\n")) {
            assertEquals(HlsManifestParseException.FailureCode.MASTER_PLAYLIST_UNSUPPORTED,
                    rejectionCode("#EXTM3U\n" + tag), tag);
        }
    }

    @Test
    void lowLatencyAndUnknownTagsAreRejected() {
        for (String tag : List.of(
                "#EXT-X-PART:DURATION=0.5,URI=\"part-1.ts\"\n",
                "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"next.ts\"\n",
                "#EXT-X-SKIP:SKIPPED-SEGMENTS=3\n",
                "#EXT-X-UNKNOWN-TAG:1\n")) {
            assertEquals(HlsManifestParseException.FailureCode.UNSUPPORTED_PLAYLIST,
                    rejectionCode("#EXTM3U\n" + tag), tag);
        }
    }

    @Test
    void gapSegmentIsRejected() {
        assertEquals(HlsManifestParseException.FailureCode.GAP_SEGMENT_UNSUPPORTED,
                rejectionCode("#EXTM3U\n#EXTINF:1,\n#EXT-X-GAP\nseg.ts\n#EXT-X-ENDLIST\n"));
    }

    @Test
    void playlistTypeMustBeVod() {
        assertEquals(HlsManifestParseException.FailureCode.PLAYLIST_TYPE_NOT_VOD,
                rejectionCode("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n"));
        assertEquals(HlsManifestParseException.FailureCode.PLAYLIST_TYPE_NOT_VOD,
                rejectionCode("#EXTM3U\n#EXT-X-PLAYLIST-TYPE\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n"));
        assertEquals(new BigDecimal("3.5"), parse("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:3.5,\nseg.ts\n"
                + "#EXT-X-ENDLIST\n").durationSeconds());
    }

    @Test
    void opaqueMetadataTagsDoNotChangeTheDuration() {
        String manifest = "#EXTM3U\n"
                + "#EXT-X-VERSION:7\n"
                + "#EXT-X-TARGETDURATION:10\n"
                + "#EXT-X-MEDIA-SEQUENCE:42\n"
                + "#EXT-X-DISCONTINUITY-SEQUENCE:7\n"
                + "#EXT-X-INDEPENDENT-SEGMENTS\n"
                + "#EXT-X-START:TIME-OFFSET=0\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example.invalid/k?token=SECRET-MARKER\"\n"
                + "#EXT-X-MAP:URI=\"init.example.invalid.mp4\"\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2026-10-24T00:00:00Z\n"
                + "#EXTINF:1.5,\n"
                + "#EXT-X-BYTERANGE:1000@0\n"
                + "#EXT-X-DISCONTINUITY\n"
                + "seg-1.ts\n"
                + "#EXTINF:2,\n"
                + "https://cdn.example.invalid/v/seg-2.ts?token=SECRET-MARKER\n"
                + "#EXT-X-ENDLIST\n";

        HlsMediaDurationCandidate candidate = parse(manifest);

        assertEquals(new BigDecimal("3.5"), candidate.durationSeconds());
        assertEquals(2, candidate.segmentCount());
        assertFalse(candidate.toString().contains("SECRET-MARKER"));
        assertFalse(candidate.toString().contains("example.invalid"));
    }

    @Test
    void rejectionNeverLeaksManifestContent() {
        String manifest = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example.invalid/k?token=SECRET-MARKER\"\n"
                + "#EXTINF:1,\n"
                + "https://cdn.example.invalid/v/seg-1.ts?token=SECRET-MARKER\n"
                + "#EXT-X-UNKNOWN-TAG:1\n"
                + "#EXT-X-ENDLIST\n";

        HlsManifestParseException failure = assertThrows(HlsManifestParseException.class,
                () -> HlsMediaDurationParser.parse(manifest.getBytes(StandardCharsets.UTF_8)));

        assertEquals(HlsManifestParseException.FailureCode.UNSUPPORTED_PLAYLIST, failure.code());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("SECRET-MARKER"));
        assertFalse(failure.getMessage().contains("example.invalid"));
        assertFalse(failure.getMessage().contains("#EXT-X-KEY"));
    }

    @Test
    void parserIsStatelessAndDeclaresNoIoOrNetworkCapability() throws IOException {
        for (Field field : HlsMediaDurationParser.class.getDeclaredFields()) {
            assertTrue(Modifier.isStatic(field.getModifiers()), "解析器不应有实例字段：" + field.getName());
        }
        String source = Files.readString(Path.of(
                "src/main/java/com/feibijiubi/backend/videosource/integration/media/HlsMediaDurationParser.java"),
                StandardCharsets.UTF_8);
        for (String forbidden : List.of("java.io", "java.net", "HttpClient", "Socket", "ProcessBuilder", "Files.",
                "URL")) {
            assertFalse(source.contains(forbidden), "解析器源码不应出现 " + forbidden);
        }
    }

    private static HlsMediaDurationCandidate parse(String manifest) {
        return HlsMediaDurationParser.parse(manifest.getBytes(StandardCharsets.UTF_8));
    }

    private static HlsManifestParseException.FailureCode rejectionCode(String manifest) {
        return rejectionCode(manifest.getBytes(StandardCharsets.UTF_8));
    }

    private static HlsManifestParseException.FailureCode rejectionCode(byte[] manifest) {
        HlsManifestParseException failure = assertThrows(HlsManifestParseException.class,
                () -> HlsMediaDurationParser.parse(manifest));
        assertNull(failure.getCause());
        return failure.code();
    }

    /** 清单里每个结构行都以 {@code LF} 结尾，因此换行符个数就是行数。 */
    private static int countLines(String manifest) {
        int lines = 0;
        for (int i = 0; i < manifest.length(); i++) {
            if (manifest.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String singleSegmentManifest(String durationToken) {
        return "#EXTM3U\n#EXTINF:" + durationToken + ",\nseg.ts\n#EXT-X-ENDLIST\n";
    }

    private static String manifestWithSegmentCount(int segmentCount) {
        StringBuilder manifest = new StringBuilder(segmentCount * 18 + 32);
        manifest.append("#EXTM3U\n");
        for (int i = 0; i < segmentCount; i++) {
            manifest.append("#EXTINF:1,\nseg.ts\n");
        }
        manifest.append("#EXT-X-ENDLIST\n");
        return manifest.toString();
    }

    private static String manifestWithExactLineCount(int lineCount) {
        StringBuilder manifest = new StringBuilder(lineCount * 6 + 32);
        manifest.append("#EXTM3U\n");
        for (int i = 0; i < lineCount - 4; i++) {
            manifest.append("#note\n");
        }
        manifest.append("#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n");
        return manifest.toString();
    }

    private static String manifestWithCommentLineOfLength(int length) {
        StringBuilder manifest = new StringBuilder(length + 64);
        manifest.append("#EXTM3U\n#");
        for (int i = 1; i < length; i++) {
            manifest.append('x');
        }
        manifest.append("\n#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n");
        return manifest.toString();
    }

    /** 用固定长度的注释行把清单精确填充到 {@code totalBytes} 字节。 */
    private static byte[] manifestWithExactSize(int totalBytes) {
        String head = "#EXTM3U\n";
        String tail = "#EXTINF:1,\nseg.ts\n#EXT-X-ENDLIST\n";
        int remaining = totalBytes - head.length() - tail.length();
        int fullLines = (remaining - 2) / 256;
        int lastLine = remaining - fullLines * 256;
        StringBuilder manifest = new StringBuilder(totalBytes);
        manifest.append(head);
        for (int i = 0; i < fullLines; i++) {
            appendCommentLine(manifest, 256);
        }
        appendCommentLine(manifest, lastLine);
        manifest.append(tail);
        return manifest.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendCommentLine(StringBuilder manifest, int lineBytes) {
        manifest.append('#');
        for (int i = 1; i < lineBytes - 1; i++) {
            manifest.append('x');
        }
        manifest.append('\n');
    }
}
