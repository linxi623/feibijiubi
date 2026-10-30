package com.feibijiubi.backend.videosource.integration.media;

import com.feibijiubi.backend.videosource.integration.media.HlsManifestParseException.FailureCode;
import com.feibijiubi.backend.videosource.integration.transport.CmsHttpRequestException;
import com.feibijiubi.backend.videosource.integration.transport.RestrictedManifestHttpClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * M2-A：{@link HlsDurationProbe} 的纯单元测试 —— 不联网、不启动 Spring、不连数据库、不读配置。
 *
 * <p>做法是<b>只</b>把受限媒体清单客户端换成 Mockito mock（它返回的 {@code byte[]} 是"已经取到的清单字节"），
 * 解析器用<b>真实</b>的 {@link HlsMediaDurationParser}。于是覆盖的是真实的"取字节 → 解析"链路，而不是又一层
 * mock。</p>
 *
 * <p>断言的是本类声明的契约：一次调用只有一次 fetch、返回值就是解析器的候选值（不新增字段、不伪造探测时间或
 * "已确认"）、master/直播清单拒绝后不再发任何请求、传输与解析异常原样上抛、失败路径不泄漏地址或清单原文。</p>
 *
 * <p>这些用例<b>不</b>证明：地址可访问、内容已授权、时长正确、清单永久不变，也不构成任何真实网络验收
 * （{@link RestrictedManifestHttpClient} 的传输行为由 {@code RestrictedManifestHttpClientTests} 离线覆盖）。</p>
 */
class HlsDurationProbeTests {

    private static final String CANDIDATE_URL = "https://media.example.invalid/a/list.m3u8";

    /** 合成敏感标记：代表上游可能塞进地址或清单的自由文本，用来验证失败路径不把它带出去。 */
    private static final String SECRET_MARKER = "SECRET-MARKER-7f3a";

    private static final String MEDIA_PLAYLIST = "#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXTINF:9.009,\n"
            + "seg-1.ts\n"
            + "#EXTINF:9.009,第二段\n"
            + "seg-2.ts\n"
            + "#EXT-X-ENDLIST\n";

    private static final String MASTER_PLAYLIST = "#EXTM3U\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=1280000\n"
            + "v0/index.m3u8?" + SECRET_MARKER + "\n";

    private static final String LIVE_PLAYLIST = "#EXTM3U\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXTINF:9.009,\n"
            + "seg-1.ts\n";

    @Test
    void rejectsANullManifestClient() {
        assertThrows(IllegalArgumentException.class, () -> new HlsDurationProbe(null));
    }

    @Test
    void probeReturnsTheParserCandidateForAMediaPlaylist() {
        RestrictedManifestHttpClient manifestClient = mock(RestrictedManifestHttpClient.class);
        byte[] manifest = MEDIA_PLAYLIST.getBytes(StandardCharsets.UTF_8);
        when(manifestClient.fetchManifest(CANDIDATE_URL)).thenReturn(manifest);

        HlsMediaDurationCandidate candidate = new HlsDurationProbe(manifestClient).probe(CANDIDATE_URL);

        // 返回值就是 M1 解析器对同一份字节给出的候选值：没有新增字段、没有探测时间、没有"已确认"。
        assertEquals(HlsMediaDurationParser.parse(manifest), candidate);
        assertEquals(new BigDecimal("18.018"), candidate.durationSeconds());
        assertEquals(2, candidate.segmentCount());
        verify(manifestClient, times(1)).fetchManifest(CANDIDATE_URL);
        verifyNoMoreInteractions(manifestClient);
    }

    @Test
    void masterPlaylistIsRefusedWithoutAnyFollowUpRequest() {
        RestrictedManifestHttpClient manifestClient = mock(RestrictedManifestHttpClient.class);
        when(manifestClient.fetchManifest(CANDIDATE_URL))
                .thenReturn(MASTER_PLAYLIST.getBytes(StandardCharsets.UTF_8));

        HlsManifestParseException failure = assertThrows(HlsManifestParseException.class,
                () -> new HlsDurationProbe(manifestClient).probe(CANDIDATE_URL));

        // 不自动挑选或递归抓取媒体清单：拒绝之后没有任何追加请求。
        assertEquals(FailureCode.MASTER_PLAYLIST_UNSUPPORTED, failure.code());
        verify(manifestClient, times(1)).fetchManifest(anyString());
        verifyNoMoreInteractions(manifestClient);
    }

    @Test
    void livePlaylistWithoutEndlistIsRefusedWithoutFetchingAnySegment() {
        RestrictedManifestHttpClient manifestClient = mock(RestrictedManifestHttpClient.class);
        when(manifestClient.fetchManifest(CANDIDATE_URL))
                .thenReturn(LIVE_PLAYLIST.getBytes(StandardCharsets.UTF_8));

        HlsManifestParseException failure = assertThrows(HlsManifestParseException.class,
                () -> new HlsDurationProbe(manifestClient).probe(CANDIDATE_URL));

        assertEquals(FailureCode.ENDLIST_MISSING, failure.code());
        verify(manifestClient, times(1)).fetchManifest(anyString());
        verifyNoMoreInteractions(manifestClient);
    }

    @Test
    void transportFailureIsPropagatedUnchangedWithASingleFetch() {
        RestrictedManifestHttpClient manifestClient = mock(RestrictedManifestHttpClient.class);
        CmsHttpRequestException transportFailure = mock(CmsHttpRequestException.class);
        when(transportFailure.reason()).thenReturn(CmsHttpRequestException.Reason.UPSTREAM_STATUS);
        when(transportFailure.upstreamStatus()).thenReturn(302);
        when(manifestClient.fetchManifest(CANDIDATE_URL)).thenThrow(transportFailure);

        CmsHttpRequestException thrown = assertThrows(CmsHttpRequestException.class,
                () -> new HlsDurationProbe(manifestClient).probe(CANDIDATE_URL));

        // 不捕获、不重新分类、不重试：就是传输层那一个实例。
        assertSame(transportFailure, thrown);
        assertEquals(CmsHttpRequestException.Reason.UPSTREAM_STATUS, thrown.reason());
        assertEquals(302, thrown.upstreamStatus());
        verify(manifestClient, times(1)).fetchManifest(anyString());
        verifyNoMoreInteractions(manifestClient);
    }

    @Test
    void parseFailureNeverEchoesTheCandidateUrlOrTheManifestText() {
        RestrictedManifestHttpClient manifestClient = mock(RestrictedManifestHttpClient.class);
        when(manifestClient.fetchManifest(CANDIDATE_URL))
                .thenReturn(MASTER_PLAYLIST.getBytes(StandardCharsets.UTF_8));

        HlsManifestParseException failure = assertThrows(HlsManifestParseException.class,
                () -> new HlsDurationProbe(manifestClient).probe(CANDIDATE_URL));

        assertEquals(FailureCode.MASTER_PLAYLIST_UNSUPPORTED.description(), failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains(SECRET_MARKER));
        assertFalse(failure.getMessage().contains("media.example.invalid"));
        assertFalse(failure.getMessage().contains("index.m3u8"));
    }

    @Test
    void emptyManifestBodyIsRefusedWithoutAnyFollowUpRequest() {
        RestrictedManifestHttpClient manifestClient = mock(RestrictedManifestHttpClient.class);
        when(manifestClient.fetchManifest(CANDIDATE_URL)).thenReturn(new byte[0]);

        HlsManifestParseException failure = assertThrows(HlsManifestParseException.class,
                () -> new HlsDurationProbe(manifestClient).probe(CANDIDATE_URL));

        assertEquals(FailureCode.MANIFEST_EMPTY, failure.code());
        verify(manifestClient, times(1)).fetchManifest(anyString());
        verifyNoMoreInteractions(manifestClient);
    }
}
