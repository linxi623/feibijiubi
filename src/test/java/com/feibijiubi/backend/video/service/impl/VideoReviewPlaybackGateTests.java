package com.feibijiubi.backend.video.service.impl;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.user.mapper.UserFollowMapper;
import com.feibijiubi.backend.user.mapper.UserMapper;
import com.feibijiubi.backend.video.dto.VideoReviewDTO;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.entity.LocalEpisodePlaybackLine;
import com.feibijiubi.backend.videosource.integration.ExternalMediaAddressDigest;
import com.feibijiubi.backend.videosource.mapper.VideoPlaybackMapper;
import com.feibijiubi.backend.videosource.service.ExternalPlaybackLineResolver;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceRegistry;
import com.feibijiubi.backend.videostats.mapper.VideoStatusMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M6-A review gate with the <b>real</b> shared resolver: the playback feature flag alone cannot approve
 * an external video. Default-off must not query S2/M5 tables at all; explicit-on still requires one line
 * with confirmed HLS evidence, matching durations, an enabled source, the media allowlist and the stored
 * address hash.
 */
class VideoReviewPlaybackGateTests {

    private static final String SOURCE = "demo";
    private static final String ADDRESS = "https://media.example.invalid/hls/index.m3u8";
    private static final String MANIFEST_HASH = "a".repeat(64);

    final VideoMapper videoMapper = mock(VideoMapper.class);
    final VideoStatusMapper videoStatusMapper = mock(VideoStatusMapper.class);
    final UserMapper userMapper = mock(UserMapper.class);
    final UserFollowMapper userFollowMapper = mock(UserFollowMapper.class);
    final VideoPlaybackMapper playbackMapper = mock(VideoPlaybackMapper.class);

    final Video video = new Video();
    final LocalVideoEpisode episode = new LocalVideoEpisode();
    final LocalEpisodePlaybackLine line = new LocalEpisodePlaybackLine();
    final ExternalVideoSourceProperties properties = new ExternalVideoSourceProperties();

    @BeforeEach
    void setup() {
        video.setVid(1);
        video.setStatus((byte) 0);
        video.setVideoUrl(null);
        video.setVideoKey(null);
        video.setDuration(100.0);
        when(videoMapper.selectByVid(1)).thenReturn(video);

        episode.setId(10L);
        episode.setSeriesId(20L);
        episode.setVid(1);
        episode.setDurationSeconds(100.0);

        line.setBindingId(5L);
        line.setCandidateId(6L);
        line.setSourceCode(SOURCE);
        line.setLabel("线路一");
        line.setAddress(ADDRESS);
        line.setAddressHash(ExternalMediaAddressDigest.sha256Hex(ADDRESS));
        line.setProbeStatus("SUCCEEDED");
        line.setCandidateSeconds(100.0);
        line.setManifestHash(MANIFEST_HASH);
        line.setConfirmedSeconds(100.0);
        line.setConfirmedAt(LocalDateTime.now());

        var source = new ExternalVideoSourceProperties.Source();
        source.setEnabled(true);
        source.setCode(SOURCE);
        source.setName("示例来源");
        source.setApiBase("https://api.example.invalid/api.php");
        source.setApiAllowedEndpoints(new LinkedHashSet<>(Set.of("api.example.invalid:443")));
        source.setMediaAllowedEndpoints(new LinkedHashSet<>(Set.of("media.example.invalid:443")));
        properties.setSources(List.of(source));
        properties.setPlaybackEnabled(true);
    }

    VideoReviewImpl service(boolean playbackEnabled) {
        properties.setPlaybackEnabled(playbackEnabled);
        return new VideoReviewImpl(videoMapper, videoStatusMapper, userMapper, userFollowMapper,
                new ExternalPlaybackLineResolver(playbackMapper,
                        new ExternalVideoSourceRegistry(properties)));
    }

    private void stubExternalBinding() {
        when(playbackMapper.selectEpisodeByVid(1)).thenReturn(episode);
        when(playbackMapper.selectPlaybackLines(10L)).thenReturn(List.of(line));
    }

    static VideoReviewDTO approve() {
        VideoReviewDTO request = new VideoReviewDTO();
        request.setResult("APPROVED");
        return request;
    }

    @Test
    void defaultOffKeepsRejectingWithoutQueryingExternalTables() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service(false).videoReview(7, 1, approve()));

        assertEquals(409, error.getCode());
        verifyNoInteractions(playbackMapper);
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void explicitOnWithOneQualifyingHlsLineAllowsApproval() {
        stubExternalBinding();
        when(videoMapper.updateReviewStatus(1, 0, 1)).thenReturn(1);

        service(true).videoReview(7, 1, approve());

        verify(videoMapper).updateReviewStatus(1, 0, 1);
        verify(playbackMapper).selectEpisodeByVid(1);
        verify(playbackMapper).selectPlaybackLines(10L);
    }

    @Test
    void explicitOnStillRejectsFailedProbeUnknownFormatOrMissingEvidence() {
        stubExternalBinding();
        line.setProbeStatus("FETCH_FAILED");

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service(true).videoReview(7, 1, approve())).getCode());
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void explicitOnStillRejectsUnconfirmedCandidate() {
        stubExternalBinding();
        line.setConfirmedAt(null);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service(true).videoReview(7, 1, approve())).getCode());
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void explicitOnStillRejectsDurationMismatch() {
        stubExternalBinding();
        line.setConfirmedSeconds(99.0);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service(true).videoReview(7, 1, approve())).getCode());
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void explicitOnStillRejectsAddressHashMismatch() {
        stubExternalBinding();
        line.setAddressHash("b".repeat(64));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service(true).videoReview(7, 1, approve())).getCode());
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void explicitOnStillRejectsAddressOutsideTheMediaAllowlist() {
        stubExternalBinding();
        line.setAddress("https://evil.example.invalid/hls/index.m3u8");
        line.setAddressHash(ExternalMediaAddressDigest.sha256Hex(line.getAddress()));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service(true).videoReview(7, 1, approve())).getCode());
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void explicitOnStillRejectsDisabledSource() {
        stubExternalBinding();
        var source = properties.getSources().get(0);
        source.setEnabled(false);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service(true).videoReview(7, 1, approve())).getCode());
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void explicitOnStillRejectsWhenVideoDurationDriftedFromEpisodeDuration() {
        stubExternalBinding();
        video.setDuration(101.0);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service(true).videoReview(7, 1, approve())).getCode());
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void oldCosApprovalNeverQueriesExternalTablesEvenWhenPlaybackIsOn() {
        video.setVideoUrl("https://cos.example.invalid/videos/1.mp4");
        video.setVideoKey("videos/1.mp4");
        when(videoMapper.updateReviewStatus(1, 0, 1)).thenReturn(1);

        service(true).videoReview(7, 1, approve());

        verify(videoMapper).updateReviewStatus(1, 0, 1);
        verifyNoInteractions(playbackMapper);
    }
}
