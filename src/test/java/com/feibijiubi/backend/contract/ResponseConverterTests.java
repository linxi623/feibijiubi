package com.feibijiubi.backend.contract;

import com.feibijiubi.backend.user.entity.User;
import com.feibijiubi.backend.user.converter.UserConverter;
import com.feibijiubi.backend.video.converter.VideoConverter;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.videostats.entity.VideoStatus;
import com.feibijiubi.backend.video.vo.AdminVideoDetailVO;
import com.feibijiubi.backend.user.vo.UserCountVO;
import com.feibijiubi.backend.user.vo.UserVO;
import com.feibijiubi.backend.video.vo.VideoDetailVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResponseConverterTests {

    @Test
    void userCountsDefaultToZero() {
        UserCountVO count = UserConverter.toUserCountVO(null, null, null, null);

        assertEquals(0, count.getFansCount());
        assertEquals(0, count.getStarCount());
        assertEquals(0L, count.getLoveCount());
        assertEquals(0, count.getVideoCount());
    }

    @Test
    void userConversionIsFlatAndHandlesNullUser() {
        assertNull(UserConverter.toUserVO(null, new UserCountVO(), false));

        User user = new User();
        user.setId(1);
        user.setUsername("tester");
        user.setNickname("测试用户");
        user.setGender((byte) 2);
        user.setCoin(10);
        user.setCreatedAt(LocalDateTime.of(2026, 7, 12, 10, 0));
        UserCountVO count = UserConverter.toUserCountVO(1, 2, 3L, 4);

        UserVO vo = UserConverter.toUserVO(user, count, null);

        assertEquals(1, vo.getId());
        assertEquals("tester", vo.getUsername());
        assertEquals("测试用户", vo.getNickname());
        assertEquals(2, vo.getGender());
        assertEquals(10, vo.getCoin());
        assertEquals(count, vo.getUserCount());
        assertFalse(vo.getSubscribed());
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

    @Test
    void adminVideoDetailCopiesFieldsWithoutExposingEntities() {
        Video video = new Video();
        video.setVid(1);
        video.setUid(2);
        video.setTitle("待审核视频");
        video.setSourceType(1);
        video.setVisibility((byte) 0);
        video.setStatus((byte) 0);

        VideoStatus status = new VideoStatus(1, 10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L);
        User author = new User();
        author.setNickname("作者");
        author.setAvatarUrl("avatar");

        AdminVideoDetailVO detail = VideoConverter.toAdminVideoDetailVO(
                video, status, author, null, null
        );

        assertEquals(1, detail.getVid());
        assertEquals("待审核视频", detail.getTitle());
        assertEquals(10L, detail.getPlayTimes());
        assertEquals(9L, detail.getLikeTimes());
        assertEquals(8L, detail.getUnlikeTimes());
        assertEquals(3L, detail.getDanmuTimes());
        assertEquals("作者", detail.getNickname());
        assertEquals(0, detail.getVideoCount());
        assertEquals(0, detail.getFansCount());

        boolean exposesEntity = Arrays.stream(AdminVideoDetailVO.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(type -> type == Video.class || type == VideoStatus.class);
        assertFalse(exposesEntity);
    }
}
