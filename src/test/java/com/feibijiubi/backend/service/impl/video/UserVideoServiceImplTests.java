package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.entity.UserVideo;
import com.feibijiubi.backend.entity.Video;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.mapper.UserVideoMapper;
import com.feibijiubi.backend.mapper.VideoMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserVideoServiceImplTests {
    @Mock
    private VideoMapper videoMapper;
    @Mock
    private VideoStatusMapper videoStatusMapper;
    @Mock
    private UserVideoMapper userVideoMapper;
    @Mock
    private UserMapper userMapper;

    private UserVideoServiceImpl userVideoService;

    @BeforeEach
    void setUp() {
        userVideoService = new UserVideoServiceImpl(videoMapper, videoStatusMapper, userVideoMapper, userMapper);
    }

    @Test
    void increasePlayCountWorksWithoutUserRecord() {
        when(videoMapper.selectPublishedByVid(1)).thenReturn(videoWithDuration(120D));
        when(videoStatusMapper.increasePlayTimes(1)).thenReturn(1);

        userVideoService.increasePlayCount(1);

        verify(videoStatusMapper).increasePlayTimes(1);
        verify(userVideoMapper, never()).ensureExists(any(), any());
        verify(userVideoMapper, never()).updatePlay(any());
    }

    @Test
    void increasePlayCountRejectsMissingVideo() {
        when(videoMapper.selectPublishedByVid(1)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userVideoService.increasePlayCount(1));

        assertEquals(404, exception.getCode());
        verify(videoStatusMapper, never()).increasePlayTimes(any());
    }

    @Test
    void increasePlayCountRejectsFailedStatusUpdate() {
        when(videoMapper.selectPublishedByVid(1)).thenReturn(videoWithDuration(120D));
        when(videoStatusMapper.increasePlayTimes(1)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userVideoService.increasePlayCount(1));

        assertEquals(500, exception.getCode());
    }

    @Test
    void savePlayProgressUpdatesRecordWithoutIncreasingCount() {
        when(videoMapper.selectPublishedByVid(1)).thenReturn(videoWithDuration(120D));
        when(userVideoMapper.selectByUidAndVid(2, 1)).thenReturn(userVideo(2, 1));
        when(userVideoMapper.updatePlay(any(UserVideo.class))).thenReturn(1);

        userVideoService.savePlayProgress(1, 2, 35.5D);

        verify(userVideoMapper).ensureExists(2, 1);
        ArgumentCaptor<UserVideo> captor = ArgumentCaptor.forClass(UserVideo.class);
        verify(userVideoMapper).updatePlay(captor.capture());
        UserVideo record = captor.getValue();
        assertEquals(2, record.getUid());
        assertEquals(1, record.getVid());
        assertEquals(35.5D, record.getPlayTime());
        assertTrue(record.getPlayedAt() != null);
        verify(videoStatusMapper, never()).increasePlayTimes(any());
    }

    @Test
    void savePlayProgressRejectsMissingUserBeforeDatabaseAccess() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> userVideoService.savePlayProgress(1, null, 10D));

        assertEquals(401, exception.getCode());
        verify(videoMapper, never()).selectPublishedByVid(any());
        verify(userVideoMapper, never()).ensureExists(any(), any());
    }

    @Test
    void savePlayProgressRejectsInvalidProgress() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> userVideoService.savePlayProgress(1, 2, Double.NaN));

        assertEquals(400, exception.getCode());
        verify(videoMapper, never()).selectPublishedByVid(any());
    }

    @Test
    void savePlayProgressRejectsProgressBeyondDuration() {
        when(videoMapper.selectPublishedByVid(1)).thenReturn(videoWithDuration(120D));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userVideoService.savePlayProgress(1, 2, 121D));

        assertEquals(400, exception.getCode());
        verify(userVideoMapper, never()).ensureExists(any(), any());
        verify(videoStatusMapper, never()).increasePlayTimes(any());
    }

    @Test
    void savePlayProgressStopsWhenRecordUpdateFails() {
        when(videoMapper.selectPublishedByVid(1)).thenReturn(videoWithDuration(120D));
        when(userVideoMapper.selectByUidAndVid(2, 1)).thenReturn(userVideo(2, 1));
        when(userVideoMapper.updatePlay(any(UserVideo.class))).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> userVideoService.savePlayProgress(1, 2, 10D));

        assertEquals(500, exception.getCode());
        verify(videoStatusMapper, never()).increasePlayTimes(any());
    }

    private UserVideo userVideo(Integer uid, Integer vid) {
        UserVideo userVideo = new UserVideo();
        userVideo.setUid(uid);
        userVideo.setVid(vid);
        return userVideo;
    }

    private Video videoWithDuration(Double duration) {
        Video video = new Video();
        video.setVid(1);
        video.setDuration(duration);
        return video;
    }
}
