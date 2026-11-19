package com.feibijiubi.backend.video.service.impl;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.user.mapper.UserFollowMapper;
import com.feibijiubi.backend.user.mapper.UserMapper;
import com.feibijiubi.backend.video.dto.VideoReviewDTO;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import com.feibijiubi.backend.videosource.service.ExternalPlaybackLineResolver;
import com.feibijiubi.backend.videostats.mapper.VideoStatusMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M5/M6-A review gate: an external episode has no local playback object (video_url / video_key NULL),
 * so it must not reach APPROVED through the only status-writing service path unless M6-A explicitly
 * enables playback and the shared line resolver confirms at least one available HLS line. Old COS rows
 * keep their previous review behavior and never touch S2/M5 tables.
 */
class VideoReviewMediaGateTests {

    final VideoMapper videoMapper = mock(VideoMapper.class);
    final VideoStatusMapper videoStatusMapper = mock(VideoStatusMapper.class);
    final UserMapper userMapper = mock(UserMapper.class);
    final UserFollowMapper userFollowMapper = mock(UserFollowMapper.class);
    final ExternalPlaybackLineResolver externalPlaybackLines = mock(ExternalPlaybackLineResolver.class);
    final VideoReviewImpl service = new VideoReviewImpl(
            videoMapper, videoStatusMapper, userMapper, userFollowMapper, externalPlaybackLines);

    static Video video(byte status, String videoUrl, String videoKey) {
        Video video = new Video();
        video.setVid(1);
        video.setStatus(status);
        video.setVideoUrl(videoUrl);
        video.setVideoKey(videoKey);
        return video;
    }

    static VideoReviewDTO review(String result) {
        VideoReviewDTO request = new VideoReviewDTO();
        request.setResult(result);
        return request;
    }

    @Test
    void externalVideoWithoutPlaybackMediaCannotBeApprovedWhenPlaybackIsOffOrNoLineQualifies() {
        Video video = video((byte) 0, null, null);
        when(videoMapper.selectByVid(1)).thenReturn(video);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.videoReview(7, 1, review("APPROVED")));

        assertEquals(409, error.getCode());
        assertTrue(error.getMessage().contains("播放媒体"));
        verify(externalPlaybackLines).hasAvailableHlsLine(video);
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void blankPlaybackFieldsAreAlsoBlocked() {
        Video video = video((byte) 0, " ", "");
        when(videoMapper.selectByVid(1)).thenReturn(video);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.videoReview(7, 1, review("APPROVED"))).getCode());
        verify(externalPlaybackLines).hasAvailableHlsLine(video);
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void externalVideoCanBeApprovedOnlyWhenTheSharedResolverConfirmsAnHlsLine() {
        Video video = video((byte) 0, null, null);
        when(videoMapper.selectByVid(1)).thenReturn(video);
        when(externalPlaybackLines.hasAvailableHlsLine(video)).thenReturn(true);
        when(videoMapper.updateReviewStatus(1, 0, 1)).thenReturn(1);

        service.videoReview(7, 1, review("APPROVED"));

        verify(externalPlaybackLines).hasAvailableHlsLine(video);
        verify(videoMapper).updateReviewStatus(1, 0, 1);
    }

    @Test
    void playbackConfigurationAloneIsNotEnoughToApprove() {
        Video video = video((byte) 0, null, null);
        when(videoMapper.selectByVid(1)).thenReturn(video);
        // Resolver returns false even though the deployment flag may be on: no qualifying line.
        when(externalPlaybackLines.hasAvailableHlsLine(video)).thenReturn(false);

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.videoReview(7, 1, review("APPROVED"))).getCode());
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void externalVideoCanStillBeRejected() {
        when(videoMapper.selectByVid(1)).thenReturn(video((byte) 0, null, null));
        when(videoMapper.updateReviewStatus(1, 0, 2)).thenReturn(1);
        VideoReviewDTO request = review("REJECTED");
        request.setReason("信息不符");

        service.videoReview(7, 1, request);

        verify(videoMapper).updateReviewStatus(1, 0, 2);
        verifyNoInteractions(externalPlaybackLines);
    }

    @Test
    void externalVideoCannotBeReapprovedFromAnExistingApprovedState() {
        when(videoMapper.selectByVid(1)).thenReturn(video((byte) 1, null, null));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.videoReview(7, 1, review("APPROVED"))).getCode());
        verify(videoMapper, never()).updateReviewStatus(anyInt(), anyInt(), anyInt());
    }

    @Test
    void oldCosVideoReviewBehaviorIsUnchangedAndNeverQueriesExternalTables() {
        when(videoMapper.selectByVid(1)).thenReturn(video((byte) 0,
                "https://cos.example.invalid/videos/1.mp4", "videos/1.mp4"));
        when(videoMapper.updateReviewStatus(1, 0, 1)).thenReturn(1);

        service.videoReview(7, 1, review("APPROVED"));

        verify(videoMapper).updateReviewStatus(1, 0, 1);
        verifyNoInteractions(externalPlaybackLines);
    }

    @Test
    void onlyTheReviewServiceWritesTheReviewStatus() throws IOException {
        List<String> callers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains(".updateReviewStatus(")) {
                    callers.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of("VideoReviewImpl.java"), callers,
                "所有可达审核写路径都必须经过 VideoReviewImpl 的媒体闸门");
        assertFalse(callers.isEmpty());
    }
}
