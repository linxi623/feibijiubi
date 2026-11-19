package com.feibijiubi.backend.videosource.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.entity.LocalEpisodePlaybackLine;
import com.feibijiubi.backend.videosource.integration.ExternalMediaAddressDigest;
import com.feibijiubi.backend.videosource.mapper.VideoPlaybackMapper;
import com.feibijiubi.backend.videosource.service.impl.VideoPlaybackServiceImpl;
import com.feibijiubi.backend.videosource.vo.VideoEpisodePageVO;
import com.feibijiubi.backend.videosource.vo.VideoPlaybackVO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M6-A public playback/episodes service: visibility identical to detail, stable line/cursor errors,
 * bounded keyset episodes, NOT_BOUND for old COS rows, fixed SQL failure messages and no writes/network.
 * Pure Mockito: no Spring, database, Redis or sockets.
 */
class VideoPlaybackServiceTests {

    private static final String ADDRESS_A = "https://media.example.invalid/hls/a.m3u8";
    private static final String ADDRESS_B = "https://media.example.invalid/hls/b.m3u8";
    private static final String MANIFEST_HASH = "a".repeat(64);
    private static final int VID = 1;
    private static final int OWNER = 7;

    final VideoMapper videoMapper = mock(VideoMapper.class);
    final VideoPlaybackMapper playbackMapper = mock(VideoPlaybackMapper.class);
    final ExternalVideoSourceProperties properties = new ExternalVideoSourceProperties();

    private VideoPlaybackServiceImpl service(Boolean playbackEnabled, boolean sourceEnabled) {
        var source = new ExternalVideoSourceProperties.Source();
        source.setEnabled(sourceEnabled);
        source.setCode("demo");
        source.setName("示例来源");
        source.setApiBase("https://api.example.invalid/api.php");
        source.setApiAllowedEndpoints(new LinkedHashSet<>(Set.of("api.example.invalid:443")));
        source.setMediaAllowedEndpoints(new LinkedHashSet<>(Set.of("media.example.invalid:443")));
        if (playbackEnabled != null) properties.setPlaybackEnabled(playbackEnabled);
        properties.setSources(List.of(source));
        return new VideoPlaybackServiceImpl(videoMapper, playbackMapper,
                new ExternalPlaybackLineResolver(playbackMapper,
                        new ExternalVideoSourceRegistry(properties)));
    }

    private VideoPlaybackServiceImpl service() {
        return service(null, true); // 不指定播放总开关，覆盖正常部署默认值。
    }

    private static Video video(byte visibility) {
        Video video = new Video();
        video.setVid(VID);
        video.setUid(OWNER);
        video.setVisibility(visibility);
        video.setDuration(100.0);
        video.setVideoUrl(null);
        video.setVideoKey(null);
        return video;
    }

    private static LocalVideoEpisode episode() {
        LocalVideoEpisode episode = new LocalVideoEpisode();
        episode.setId(10L);
        episode.setSeriesId(20L);
        episode.setVid(VID);
        episode.setDurationSeconds(100.0);
        return episode;
    }

    private static LocalEpisodePlaybackLine line(long bindingId, String address) {
        LocalEpisodePlaybackLine line = new LocalEpisodePlaybackLine();
        line.setBindingId(bindingId);
        line.setCandidateId(bindingId + 100);
        line.setSourceCode("demo");
        line.setLabel("线路" + bindingId);
        line.setAddress(address);
        line.setAddressHash(ExternalMediaAddressDigest.sha256Hex(address));
        line.setProbeStatus("SUCCEEDED");
        line.setCandidateSeconds(100.0);
        line.setManifestHash(MANIFEST_HASH);
        line.setConfirmedSeconds(100.0);
        line.setConfirmedAt(LocalDateTime.now());
        return line;
    }

    @Test
    void playbackReturnsOnlyTheSelectedAddressAndLocalLineMetadata() throws Exception {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(episode());
        when(playbackMapper.selectPlaybackLines(10L))
                .thenReturn(List.of(line(5L, ADDRESS_A), line(7L, ADDRESS_B)));

        VideoPlaybackVO vo = service().playback(99, VID, null);

        assertTrue(vo.isPlayable());
        assertEquals("OK", vo.getReason());
        assertEquals("HLS", vo.getFormat());
        assertEquals(ADDRESS_A, vo.getUrl());
        assertEquals(5L, vo.getLineId());
        assertEquals(2, vo.getLines().size());
        assertTrue(vo.getLines().get(0).available());
        assertTrue(vo.getLines().get(1).available());

        String json = new ObjectMapper().writeValueAsString(vo);
        assertTrue(json.contains(ADDRESS_A), "只应出现选中线路的地址");
        assertFalse(json.contains(ADDRESS_B), "其他线路的地址不得出现在响应中");
        assertFalse(json.contains("addressHash"));
        assertFalse(json.contains("manifestHash"));
        assertFalse(json.contains("sourceCode"));
        assertFalse(vo.toString().contains(ADDRESS_A), "播放 VO 不得通过 toString 泄露地址");
        verify(videoMapper, never()).insert(any());
        verify(videoMapper, never()).updateVideo(any());
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
        verify(videoMapper, never()).softDeleteByOwner(any(), any(), any());
    }

    @Test
    void playbackHonorsAnExplicitAvailableLine() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(episode());
        when(playbackMapper.selectPlaybackLines(10L))
                .thenReturn(List.of(line(5L, ADDRESS_A), line(7L, ADDRESS_B)));

        VideoPlaybackVO vo = service().playback(null, VID, "7");

        assertTrue(vo.isPlayable());
        assertEquals(ADDRESS_B, vo.getUrl());
        assertEquals(7L, vo.getLineId());
    }

    @Test
    void playbackInvalidLineIdIsStable400BeforeAnyVideoQuery() {
        for (String lineId : List.of("abc", "0", "-1", "01", "99999999999999999999")) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service().playback(null, VID, lineId), lineId);
            assertEquals(400, error.getCode(), lineId);
            assertEquals("播放线路不合法", error.getMessage(), lineId);
        }
        verifyNoInteractions(videoMapper, playbackMapper);
    }

    @Test
    void playbackCrossEpisodeLineIdIsStable400() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(episode());
        when(playbackMapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_A)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().playback(null, VID, "7"));

        assertEquals(400, error.getCode());
        assertEquals("播放线路不合法", error.getMessage());
    }

    @Test
    void oldCosOrUnboundVideoIsNotBoundAndRequestedLineIsStillRejected() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(null);

        VideoPlaybackVO vo = service().playback(null, VID, null);
        assertFalse(vo.isPlayable());
        assertEquals("NOT_BOUND", vo.getReason());
        assertNull(vo.getUrl());
        assertTrue(vo.getLines().isEmpty());

        assertEquals(400, assertThrows(BusinessException.class,
                () -> service().playback(null, VID, "5")).getCode());
    }

    @Test
    void playbackDisabledNeverReturnsAnAddressEvenWhenTheLineWouldQualify() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(episode());
        when(playbackMapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_A)));

        VideoPlaybackVO vo = service(false, true).playback(null, VID, null);

        assertFalse(vo.isPlayable());
        assertEquals("PLAYBACK_DISABLED", vo.getReason());
        assertNull(vo.getUrl());
        assertFalse(vo.getLines().get(0).available());
    }

    @Test
    void disabledSourceGivesStableSourceDisabledReason() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(episode());
        when(playbackMapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_A)));

        VideoPlaybackVO vo = service(true, false).playback(null, VID, null);

        assertFalse(vo.isPlayable());
        assertEquals("SOURCE_DISABLED", vo.getReason());
        assertNull(vo.getUrl());
    }

    @Test
    void privateVideoIsVisibleOnlyToItsOwnerAndAdminsHaveNoPublicBypass() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 1));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(episode());
        when(playbackMapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_A)));

        for (Integer caller : Arrays.asList(null, 99, 1)) {
            assertEquals(403, assertThrows(BusinessException.class,
                    () -> service().playback(caller, VID, null)).getCode());
        }
        assertTrue(service().playback(OWNER, VID, null).isPlayable());
    }

    @Test
    void unpublishedDeletedOrMissingVideoIs404() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(null);

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service().playback(null, VID, null)).getCode());
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service().episodes(null, VID, null, null)).getCode());
        verify(playbackMapper, never()).selectEpisodeByVid(anyInt());
    }

    @Test
    void invalidVidIs400() {
        for (Integer vid : Arrays.asList(null, 0, -1)) {
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> service().playback(null, vid, null)).getCode());
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> service().episodes(null, vid, null, null)).getCode());
        }
        verifyNoInteractions(videoMapper, playbackMapper);
    }

    @Test
    void playbackSqlFailureIsFixedAndDoesNotEchoTheSensitiveAddress() {
        when(videoMapper.selectPublishedByVid(VID))
                .thenThrow(new DataAccessResourceFailureException("address=" + ADDRESS_A));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().playback(null, VID, null));

        assertEquals(500, error.getCode());
        assertEquals("PLAYBACK_QUERY_FAILED：播放数据读取失败", error.getMessage());
        assertFalse(error.getMessage().contains(ADDRESS_A));
    }

    @Test
    void playbackLineQueryFailureIsAlsoFixed() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(episode());
        when(playbackMapper.selectPlaybackLines(10L))
                .thenThrow(new DataAccessResourceFailureException("address=" + ADDRESS_A));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().playback(null, VID, null));

        assertEquals(500, error.getCode());
        assertEquals("PLAYBACK_QUERY_FAILED：播放数据读取失败", error.getMessage());
        assertFalse(error.getMessage().contains(ADDRESS_A));
    }

    @Test
    void episodesDefaultSizeUsesBoundedKeysetAndReportsHasMoreWithoutTotals() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(episode());
        when(playbackMapper.selectVisibleEpisodesAfter(20L, 0L, null, 21))
                .thenReturn(List.of(episodeRow(11L, 101, "第一集"), episodeRow(12L, 102, "第二集")));

        VideoEpisodePageVO page = service().episodes(null, VID, null, null);

        assertEquals(2, page.items().size());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        verify(playbackMapper).selectVisibleEpisodesAfter(20L, 0L, null, 21);
        assertEquals(List.of("items", "hasMore", "nextCursor"),
                Arrays.stream(VideoEpisodePageVO.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
        assertEquals(List.of("vid", "displayTitle"),
                Arrays.stream(VideoEpisodePageVO.Item.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
    }

    @Test
    void episodesCursorIsTheLastReturnedLocalEpisodeIdAndHiddenRowsDoNotConsumeThePage() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(episode());
        // id 12 不可见，SQL 已过滤；service 只看到 11、13，并据此给出游标 11。
        when(playbackMapper.selectVisibleEpisodesAfter(20L, 0L, null, 2))
                .thenReturn(List.of(episodeRow(11L, 101, "第一集"), episodeRow(13L, 103, "第三集")));

        VideoEpisodePageVO first = service().episodes(null, VID, null, "1");
        assertEquals(1, first.items().size());
        assertEquals(101, first.items().get(0).vid());
        assertTrue(first.hasMore());
        assertEquals("11", first.nextCursor());

        when(playbackMapper.selectVisibleEpisodesAfter(20L, 11L, null, 2))
                .thenReturn(List.of(episodeRow(13L, 103, "第三集")));
        VideoEpisodePageVO second = service().episodes(null, VID, "11", "1");
        assertFalse(second.hasMore());
        assertNull(second.nextCursor());
        verify(playbackMapper).selectVisibleEpisodesAfter(20L, 11L, null, 2);
    }

    @Test
    void episodesMaximumSizeIs50() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(episode());
        when(playbackMapper.selectVisibleEpisodesAfter(20L, 0L, null, 51)).thenReturn(List.of());

        service().episodes(null, VID, null, "50");

        verify(playbackMapper).selectVisibleEpisodesAfter(20L, 0L, null, 51);
    }

    @Test
    void episodesInvalidSizeOrCursorIsStable400() {
        for (String size : List.of("0", "05", "51", "abc", "", "100")) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service().episodes(null, VID, null, size), size);
            assertEquals(400, error.getCode(), size);
            assertEquals("分页参数不合法", error.getMessage(), size);
        }
        for (String cursor : List.of("abc", "-1", "01", "99999999999999999999")) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service().episodes(null, VID, cursor, null), cursor);
            assertEquals(400, error.getCode(), cursor);
            assertEquals("分页参数不合法", error.getMessage(), cursor);
        }
        verifyNoInteractions(videoMapper, playbackMapper);
    }

    @Test
    void episodesWithoutLocalBindingIsAnEmptyPage() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(null);

        VideoEpisodePageVO page = service().episodes(null, VID, null, null);

        assertTrue(page.items().isEmpty());
        assertFalse(page.hasMore());
        assertNull(page.nextCursor());
        verify(playbackMapper, never()).selectVisibleEpisodesAfter(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void episodesUseTheSamePrivateVisibilityAsDetail() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 1));

        assertEquals(403, assertThrows(BusinessException.class,
                () -> service().episodes(99, VID, null, null)).getCode());
        verifyNoInteractions(playbackMapper);
    }

    @Test
    void episodesSqlFailureIsFixed() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video((byte) 0));
        when(playbackMapper.selectEpisodeByVid(VID)).thenReturn(episode());
        when(playbackMapper.selectVisibleEpisodesAfter(20L, 0L, null, 21))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service().episodes(null, VID, null, null));

        assertEquals(500, error.getCode());
        assertEquals("PLAYBACK_QUERY_FAILED：播放数据读取失败", error.getMessage());
    }

    @Test
    void playbackAndEpisodeReadsHaveNoNetworkWriteOrProgressDependencies() throws IOException {
        for (String path : List.of(
                "src/main/java/com/feibijiubi/backend/videosource/service/impl/VideoPlaybackServiceImpl.java",
                "src/main/java/com/feibijiubi/backend/videosource/service/ExternalPlaybackLineResolver.java")) {
            String source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            for (String forbiddenImport : List.of("ExternalMediaProbe",
                    "ExternalVideoSourceClientProvider", "Redis", "HttpClient",
                    "PublishedVideoGuard", "Transactional")) {
                assertFalse(source.lines().anyMatch(line -> line.startsWith("import ")
                                && line.contains(forbiddenImport)),
                        path + " 不得导入 " + forbiddenImport);
            }
            for (String forbiddenCall : List.of("videoMapper.insert", "videoMapper.update",
                    "videoMapper.softDelete", "getVideoUrl", "getVideoKey")) {
                assertFalse(source.contains(forbiddenCall), path + " 不得调用 " + forbiddenCall);
            }
        }
        for (Method method : VideoPlaybackMapper.class.getDeclaredMethods()) {
            assertTrue(method.getName().startsWith("select"),
                    "播放 mapper 只能是 SELECT：" + method.getName());
        }
        for (var field : VideoPlaybackServiceImpl.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().contains("Redis")
                            || field.getType().getName().contains("Http")
                            || field.getType().getName().contains("Probe")
                            || field.getType().getName().contains("Provider"),
                    "播放服务不得持有网络/Redis/探测依赖：" + field);
        }
    }

    private static LocalVideoEpisode episodeRow(long id, int vid, String displayTitle) {
        LocalVideoEpisode row = new LocalVideoEpisode();
        row.setId(id);
        row.setSeriesId(20L);
        row.setVid(vid);
        row.setDisplayTitle(displayTitle);
        return row;
    }
}
