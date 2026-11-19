package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.entity.LocalEpisodePlaybackLine;
import com.feibijiubi.backend.videosource.integration.ExternalMediaAddressDigest;
import com.feibijiubi.backend.videosource.mapper.VideoPlaybackMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M6-A line qualification: real resolver with mocked SQL and a real immutable registry. No network,
 * no database. Proves the deterministic default, strict HLS/confirmation/duration/source/media/hash
 * gates, stable 400/500 codes and that the signed address never appears in toString.
 */
class ExternalPlaybackLineResolverTests {

    private static final String ADDRESS_A = "https://media.example.invalid/hls/a.m3u8";
    private static final String ADDRESS_B = "https://media.example.invalid/hls/b.m3u8";
    private static final String ADDRESS_EVIL = "https://evil.example.invalid/hls/a.m3u8";
    private static final String MANIFEST_HASH = "a".repeat(64);

    final VideoPlaybackMapper mapper = mock(VideoPlaybackMapper.class);
    final ExternalVideoSourceProperties properties = new ExternalVideoSourceProperties();
    final Video video = new Video();
    final LocalVideoEpisode episode = new LocalVideoEpisode();

    @BeforeEach
    void setup() {
        video.setVid(1);
        video.setDuration(100.0);
        episode.setId(10L);
        episode.setSeriesId(20L);
        episode.setVid(1);
        episode.setDurationSeconds(100.0);
        when(mapper.selectEpisodeByVid(1)).thenReturn(episode);
    }

    private ExternalVideoSourceRegistry registry(boolean playbackEnabled, boolean sourceEnabled,
                                                 boolean mediaAllowed) {
        var source = new ExternalVideoSourceProperties.Source();
        source.setEnabled(sourceEnabled);
        source.setCode("demo");
        source.setName("示例来源");
        source.setApiBase("https://api.example.invalid/api.php");
        source.setApiAllowedEndpoints(new LinkedHashSet<>(Set.of("api.example.invalid:443")));
        if (mediaAllowed) {
            source.setMediaAllowedEndpoints(new LinkedHashSet<>(Set.of("media.example.invalid:443")));
        }
        properties.setPlaybackEnabled(playbackEnabled);
        properties.setSources(List.of(source));
        return new ExternalVideoSourceRegistry(properties);
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

    private ExternalPlaybackLineResolver resolver(ExternalVideoSourceRegistry registry) {
        return new ExternalPlaybackLineResolver(mapper, registry);
    }

    @Test
    void defaultSelectionIsTheFirstAvailableLineByBindingId() {
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(
                line(5L, ADDRESS_A), line(7L, ADDRESS_B)));

        ExternalPlaybackLineResolver.Resolution resolution =
                resolver(registry(true, true, true)).resolve(video, null);

        assertTrue(resolution.isPlayable());
        assertEquals(ExternalPlaybackLineResolver.REASON_OK, resolution.getReason());
        assertEquals(5L, resolution.getSelected().getLineId());
        assertEquals(ADDRESS_A, resolution.getSelected().getAddress());
        assertEquals(2, resolution.getLines().size());
        assertTrue(resolution.getLines().get(0).isAvailable());
        assertTrue(resolution.getLines().get(1).isAvailable());
    }

    @Test
    void unavailableLowerIdIsSkippedDeterministically() {
        LocalEpisodePlaybackLine failed = line(5L, ADDRESS_A);
        failed.setProbeStatus("FETCH_FAILED");
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(failed, line(7L, ADDRESS_B)));

        ExternalPlaybackLineResolver.Resolution resolution =
                resolver(registry(true, true, true)).resolve(video, null);

        assertTrue(resolution.isPlayable());
        assertEquals(7L, resolution.getSelected().getLineId());
        assertFalse(resolution.getLines().get(0).isAvailable());
    }

    @Test
    void explicitlyRequestedUnavailableLineNeverFallsBackToAnotherLine() {
        LocalEpisodePlaybackLine failed = line(5L, ADDRESS_A);
        failed.setProbeStatus("UNSUPPORTED_MANIFEST");
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(failed, line(7L, ADDRESS_B)));

        ExternalPlaybackLineResolver.Resolution resolution =
                resolver(registry(true, true, true)).resolve(video, 5L);

        assertFalse(resolution.isPlayable());
        assertEquals(ExternalPlaybackLineResolver.REASON_NO_PLAYABLE_ADDRESS, resolution.getReason());
        assertNull(resolution.getSelected());
        assertTrue(resolution.getLines().get(1).isAvailable(), "其他线路可用也不能被静默选中");
    }

    @Test
    void requestedLineFromAnotherEpisodeOrUnknownIsStable400() {
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_A)));

        BusinessException error = assertThrows(BusinessException.class,
                () -> resolver(registry(true, true, true)).resolve(video, 99L));

        assertEquals(400, error.getCode());
        assertEquals("播放线路不合法", error.getMessage());
    }

    @Test
    void noLocalEpisodeIsNotBoundAndRequestedLineIsStillValidated() {
        when(mapper.selectEpisodeByVid(1)).thenReturn(null);
        ExternalPlaybackLineResolver resolver = resolver(registry(true, true, true));

        ExternalPlaybackLineResolver.Resolution resolution = resolver.resolve(video, null);
        assertFalse(resolution.isPlayable());
        assertEquals(ExternalPlaybackLineResolver.REASON_NOT_BOUND, resolution.getReason());
        assertTrue(resolution.getLines().isEmpty());

        assertEquals(400, assertThrows(BusinessException.class,
                () -> resolver.resolve(video, 5L)).getCode());
    }

    @Test
    void noBindingRowsIsNotBound() {
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of());

        ExternalPlaybackLineResolver.Resolution resolution =
                resolver(registry(true, true, true)).resolve(video, null);

        assertFalse(resolution.isPlayable());
        assertEquals(ExternalPlaybackLineResolver.REASON_NOT_BOUND, resolution.getReason());
        assertTrue(resolution.getLines().isEmpty());
    }

    @Test
    void playbackDisabledReturnsStableReasonAndNoSelectionWithoutAnyQualification() {
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_A)));

        ExternalPlaybackLineResolver.Resolution resolution =
                resolver(registry(false, true, true)).resolve(video, null);

        assertFalse(resolution.isPlayable());
        assertEquals(ExternalPlaybackLineResolver.REASON_PLAYBACK_DISABLED, resolution.getReason());
        assertNull(resolution.getSelected());
        assertFalse(resolution.getLines().get(0).isAvailable());
    }

    @Test
    void hasAvailableHlsLineShortCircuitsWhenPlaybackIsOff() {
        VideoPlaybackMapper untouched = mock(VideoPlaybackMapper.class);
        ExternalPlaybackLineResolver resolver = new ExternalPlaybackLineResolver(
                untouched, registry(false, true, true));

        assertFalse(resolver.hasAvailableHlsLine(video));
        verifyNoInteractions(untouched);
    }

    @Test
    void disabledSourceGivesSourceDisabledReason() {
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_A)));

        ExternalPlaybackLineResolver.Resolution resolution =
                resolver(registry(true, false, true)).resolve(video, null);

        assertFalse(resolution.isPlayable());
        assertEquals(ExternalPlaybackLineResolver.REASON_SOURCE_DISABLED, resolution.getReason());
        assertFalse(resolution.getLines().get(0).isAvailable());
    }

    @Test
    void sourceWithoutMediaAllowlistCannotPlay() {
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_A)));

        ExternalPlaybackLineResolver.Resolution resolution =
                resolver(registry(true, true, false)).resolve(video, null);

        assertFalse(resolution.isPlayable());
        assertEquals(ExternalPlaybackLineResolver.REASON_NO_PLAYABLE_ADDRESS, resolution.getReason());
    }

    @Test
    void addressHashMismatchRejectsTheLine() {
        LocalEpisodePlaybackLine tampered = line(5L, ADDRESS_A);
        tampered.setAddressHash(ExternalMediaAddressDigest.sha256Hex(ADDRESS_B));
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(tampered));

        ExternalPlaybackLineResolver.Resolution resolution =
                resolver(registry(true, true, true)).resolve(video, null);

        assertFalse(resolution.isPlayable());
        assertEquals(ExternalPlaybackLineResolver.REASON_NO_PLAYABLE_ADDRESS, resolution.getReason());
        assertNull(resolution.getSelected());
    }

    @Test
    void addressOutsideTheMediaAllowlistIsRejected() {
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_EVIL)));

        ExternalPlaybackLineResolver.Resolution resolution =
                resolver(registry(true, true, true)).resolve(video, null);

        assertFalse(resolution.isPlayable());
        assertEquals(ExternalPlaybackLineResolver.REASON_NO_PLAYABLE_ADDRESS, resolution.getReason());
    }

    @Test
    void unconfirmedCandidateIsNeverPlayable() {
        LocalEpisodePlaybackLine unconfirmed = line(5L, ADDRESS_A);
        unconfirmed.setConfirmedAt(null);
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(unconfirmed));

        assertFalse(resolver(registry(true, true, true)).resolve(video, null).isPlayable());
    }

    @Test
    void missingManifestEvidenceOrCandidateSecondsIsNeverPlayable() {
        LocalEpisodePlaybackLine noManifest = line(5L, ADDRESS_A);
        noManifest.setManifestHash(null);
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(noManifest));
        assertFalse(resolver(registry(true, true, true)).resolve(video, null).isPlayable());

        LocalEpisodePlaybackLine noCandidateSeconds = line(5L, ADDRESS_A);
        noCandidateSeconds.setCandidateSeconds(null);
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(noCandidateSeconds));
        assertFalse(resolver(registry(true, true, true)).resolve(video, null).isPlayable());
    }

    @Test
    void confirmedDurationMustMatchBothEpisodeAndVideoUnifiedDuration() {
        LocalEpisodePlaybackLine mismatch = line(5L, ADDRESS_A);
        mismatch.setConfirmedSeconds(99.0);
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(mismatch));
        assertFalse(resolver(registry(true, true, true)).resolve(video, null).isPlayable());

        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_A)));
        video.setDuration(101.0);
        assertFalse(resolver(registry(true, true, true)).resolve(video, null).isPlayable());
    }

    @Test
    void signedAddressIsReturnedByteForByteWithoutNormalization() {
        String signed = "https://media.example.invalid/hls/a.m3u8?sign=AbC%2F123%3D&expires=1700000000";
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, signed)));

        ExternalPlaybackLineResolver.Resolution resolution =
                resolver(registry(true, true, true)).resolve(video, null);

        assertTrue(resolution.isPlayable());
        assertEquals(signed, resolution.getSelected().getAddress(),
                "签名 query/百分号编码必须原样返回，不 trim、不重排、不重新编码");
    }

    @Test
    void overflowBeyond32BindingsFailsFixedWithoutAddress() {
        List<LocalEpisodePlaybackLine> rows = new ArrayList<>();
        for (long id = 1; id <= 33; id++) {
            rows.add(line(id, ADDRESS_A));
        }
        when(mapper.selectPlaybackLines(10L)).thenReturn(rows);

        BusinessException error = assertThrows(BusinessException.class,
                () -> resolver(registry(true, true, true)).resolve(video, null));

        assertEquals(500, error.getCode());
        assertTrue(error.getMessage().contains("PLAYBACK_QUERY_FAILED"));
        assertFalse(error.getMessage().contains(ADDRESS_A));
    }

    @Test
    void sqlFailureBecomesFixedMessageWithoutTheSensitiveAddress() {
        when(mapper.selectEpisodeByVid(1))
                .thenThrow(new DataAccessResourceFailureException("address=" + ADDRESS_A));

        BusinessException error = assertThrows(BusinessException.class,
                () -> resolver(registry(true, true, true)).resolve(video, null));

        assertEquals(500, error.getCode());
        assertEquals("PLAYBACK_QUERY_FAILED：播放数据读取失败", error.getMessage());
        assertFalse(error.getMessage().contains(ADDRESS_A));
    }

    @Test
    void resolutionAndLineToStringDoNotExposeTheAddress() {
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_A)));
        ExternalPlaybackLineResolver.Resolution resolution =
                resolver(registry(true, true, true)).resolve(video, null);

        assertFalse(resolution.toString().contains(ADDRESS_A));
        assertFalse(resolution.getSelected().toString().contains(ADDRESS_A));
        assertFalse(resolution.getLines().get(0).toString().contains(ADDRESS_A));
    }

    @Test
    void hasAvailableHlsLineRequiresBothTheFlagAndAQualifyingLine() {
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(line(5L, ADDRESS_A)));

        assertTrue(resolver(registry(true, true, true)).hasAvailableHlsLine(video));

        LocalEpisodePlaybackLine failed = line(5L, ADDRESS_A);
        failed.setProbeStatus("FETCH_FAILED");
        when(mapper.selectPlaybackLines(10L)).thenReturn(List.of(failed));
        assertFalse(resolver(registry(true, true, true)).hasAvailableHlsLine(video));
    }

    @Test
    void sharedHlsEvidencePredicateIsTheM6aGate() {
        assertTrue(ExternalPlaybackLineResolver.hasSuccessfulHlsEvidence(
                "SUCCEEDED", MANIFEST_HASH, 100.0));
        assertFalse(ExternalPlaybackLineResolver.hasSuccessfulHlsEvidence(
                "FETCH_FAILED", MANIFEST_HASH, 100.0));
        assertFalse(ExternalPlaybackLineResolver.hasSuccessfulHlsEvidence(
                "SUCCEEDED", null, 100.0));
        assertFalse(ExternalPlaybackLineResolver.hasSuccessfulHlsEvidence(
                "SUCCEEDED", "A".repeat(64), 100.0));
        assertFalse(ExternalPlaybackLineResolver.hasSuccessfulHlsEvidence(
                "SUCCEEDED", MANIFEST_HASH, null));
        assertFalse(ExternalPlaybackLineResolver.hasSuccessfulHlsEvidence(
                "SUCCEEDED", MANIFEST_HASH, 0.0));
        assertFalse(ExternalPlaybackLineResolver.hasSuccessfulHlsEvidence(
                "SUCCEEDED", MANIFEST_HASH, Double.NaN));
    }
}
