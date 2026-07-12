package com.feibijiubi.backend.converter;

import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.entity.Video;
import com.feibijiubi.backend.vo.UserCountVO;
import com.feibijiubi.backend.vo.UserPublicProfileVO;
import com.feibijiubi.backend.vo.VideoDetailVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResponseConverterTests {

    @Test
    void userCountsDefaultToZero() {
        UserCountVO count = UserConverter.toUserCountVO(null, null, null, null);

        assertEquals(0, count.getFansCount());
        assertEquals(0, count.getStarCount());
        assertEquals(0, count.getLoveCount());
        assertEquals(0, count.getVideoCount());
    }

    @Test
    void publicProfileHandlesNullUser() {
        assertNull(UserConverter.toUserPublicProfileVO(null, new UserCountVO(), false));
    }

    @Test
    void videoDetailContainsAuthorInformationAndSafeDefaults() {
        Video video = new Video();
        video.setVid(1);
        video.setUid(2);
        video.setTitle("测试视频");

        User author = new User();
        author.setNickname("作者");
        author.setAvatarUrl("https://example.com/avatar.png");

        VideoDetailVO detail = VideoConverter.toVideoDetailVO(
                video, null, null, author, null, null, null
        );

        assertEquals("作者", detail.getNickname());
        assertEquals("https://example.com/avatar.png", detail.getAvatarUrl());
        assertEquals(0, detail.getVideoCount());
        assertEquals(0, detail.getFansCount());
        assertFalse(detail.getSubscribed());
        assertFalse(detail.getLiked());
        assertFalse(detail.getCollected());
        assertEquals((byte) 0, detail.getCoin());
        assertEquals(0D, detail.getPlayTime());
    }
}
