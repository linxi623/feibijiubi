package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.infrastructure.redis.RedisOperationException;
import com.feibijiubi.backend.videostats.exception.VideoStatusCounterRangeException;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.video.dto.CursorDTO;
import com.feibijiubi.backend.user.entity.User;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.videostats.entity.VideoStatus;
import com.feibijiubi.backend.video.mapper.UploadTempFileMapper;
import com.feibijiubi.backend.user.mapper.UserFollowMapper;
import com.feibijiubi.backend.user.mapper.UserMapper;
import com.feibijiubi.backend.interaction.mapper.UserVideoMapper;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import com.feibijiubi.backend.videostats.mapper.VideoStatusMapper;
import com.feibijiubi.backend.videostats.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.user.service.impl.UserServiceImpl;
import com.feibijiubi.backend.video.service.impl.VideoReviewImpl;
import com.feibijiubi.backend.video.service.impl.VideoServiceImpl;
import com.feibijiubi.backend.videostats.service.impl.VideoStatusServiceImpl;
import com.feibijiubi.backend.infrastructure.storage.service.FileStorageService;
import com.feibijiubi.backend.shared.JsonUtils;
import com.feibijiubi.backend.infrastructure.redis.RedisKeyUtils;
import com.feibijiubi.backend.infrastructure.redis.RedisUtils;
import com.feibijiubi.backend.infrastructure.redis.operation.RedisHashOperations;
import com.feibijiubi.backend.video.vo.AdminVideoDetailVO;
import com.feibijiubi.backend.video.vo.CursorPageVO;
import com.feibijiubi.backend.user.vo.UserVO;
import com.feibijiubi.backend.video.vo.VideoDetailVO;
import com.feibijiubi.backend.video.vo.VideoListItemVO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * T13 读路径一致性（模式 B：面向用户的统计读仍以 MySQL 为唯一来源）。
 *
 * <p>纯 Mockito，不启动 Spring，也不连接 Redis/MySQL/RabbitMQ。这里断言的是生产代码的行为：
 * 详情/Feed/用户获赞数/管理员详情只经由 Mapper 读 {@code video_status}；统计行缺失与数据库故障
 * 都显式失败，不会被解释成 0。</p>
 *
 * <p>Redis {@code current} 的读取方不止一个，因此不能把某个方法说成“唯一的 Redis 统计读”：
 * 管理端单视频只读对账 {@code VideoStatusReconciliationServiceImpl#readRedisSample} 也会采样
 * {@code current}（只读诊断，没有用户可见统计响应）。本类补充的是未接线只读门面
 * {@code VideoStatusServiceImpl#getByVid} 的 miss / 损坏 / 门闩 / 故障语义。</p>
 *
 * <p>为什么用“服务不声明 Redis 依赖”来证明缓存状态不影响结果：这三个服务根本没有可注入的
 * Redis 组件，因此 miss、坏字段、重建门闩、Redis 故障在它们的查询链路上不可达。加一条反向断言
 * （结构上不允许 Redis 字段）比伪造一个无法注入的缓存状态更接近事实，也能在有人给读路径接入
 * Redis 实时读时立即失败，从而必须先改 T13 决定与文档。</p>
 */
class VideoStatusReadConsistencyTests {

    private static final int VID = 4242;
    private static final int UID = 77;
    private static final int ADMIN_UID = 1;
    private static final long THREE_BILLION = 3_000_000_000L;
    /** 2^53 + 1: not representable as a double, so a float/lossy conversion would round it away. */
    private static final long ABOVE_EXACT_DOUBLE = 9_007_199_254_740_993L;
    private static final long PERSONAL_LIKES = 9_000_000_000L;

    private static final String[] COUNTER_FIELDS = {
            "playTimes", "likeTimes", "unlikeTimes", "commentTimes",
            "coinTimes", "shareTimes", "collectTimes", "danmuTimes"};

    private static final Path VIDEO_MAPPER = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/VideoMapper.xml");
    private static final Path VIDEO_STATUS_MAPPER = Path.of(
            "src/main/resources/com/feibijiubi/backend/mapper/VideoStatusMapper.xml");

    private final VideoMapper videoMapper = mock(VideoMapper.class);
    private final VideoStatusMapper videoStatusMapper = mock(VideoStatusMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final UserFollowMapper userFollowMapper = mock(UserFollowMapper.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);

    private final VideoServiceImpl videoService = new VideoServiceImpl(
            mock(UploadTempFileMapper.class),
            videoMapper,
            videoStatusMapper,
            fileStorageService,
            mock(UserVideoMapper.class),
            userMapper,
            userFollowMapper);

    private final UserServiceImpl userService = new UserServiceImpl(
            userMapper,
            fileStorageService,
            userFollowMapper,
            videoMapper,
            videoStatusMapper);

    // ------------------------------------------------------------------ 详情

    @Test
    void detailReturnsTheDatabaseRowCountersExactly() {
        Video video = publishedVideo(VID, UID, (byte) 0);
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(video);
        when(videoStatusMapper.selectByVid(VID)).thenReturn(status(
                THREE_BILLION, 20L, 1L, 4L, 2L, 6L, 3L, 5L));
        when(userMapper.selectById(UID)).thenReturn(activeUser(UID));
        when(videoMapper.countVideoByUid(UID)).thenReturn(3);
        when(userFollowMapper.countFans(UID)).thenReturn(4);

        VideoDetailVO detail = videoService.getVideoDetail(null, VID);

        assertEquals(THREE_BILLION, detail.getPlayTimes());
        assertEquals(20L, detail.getLikeTimes());
        assertEquals(2L, detail.getCoinTimes());
        assertEquals(3L, detail.getCollectTimes());
        assertEquals(4L, detail.getCommentTimes());
        assertEquals(5L, detail.getDanmuTimes());
        assertEquals(6L, detail.getShareTimes());
        assertEquals(3, detail.getVideoCount());
        assertEquals(4, detail.getFansCount());
        verify(videoStatusMapper).selectByVid(VID);
        // 读到 DB 行后不再走任何“按字段补齐”的第二次统计查询。
        verifyNoMoreInteractions(videoStatusMapper);
    }

    @Test
    void detailWithoutStatisticsRowFailsInsteadOfReportingZero() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(publishedVideo(VID, UID, (byte) 0));
        when(videoStatusMapper.selectByVid(VID)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> videoService.getVideoDetail(null, VID));

        assertEquals(500, e.getCode());
        assertEquals("视频统计数据异常", e.getMessage());
        // 统计行缺失时连作者/粉丝都不再查询，不会拼出一个半成品 VO。
        verifyNoInteractions(userMapper, userFollowMapper);
    }

    @Test
    void detailPropagatesDatabaseFailureInsteadOfZeroCounters() {
        when(videoMapper.selectPublishedByVid(VID)).thenReturn(publishedVideo(VID, UID, (byte) 0));
        when(videoStatusMapper.selectByVid(VID))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));

        DataAccessResourceFailureException e = assertThrows(DataAccessResourceFailureException.class,
                () -> videoService.getVideoDetail(null, VID));

        assertEquals("mysql down", e.getMessage());
    }

    // ------------------------------------------------------------------ 管理员详情

    private final VideoReviewImpl videoReviewService = new VideoReviewImpl(
            videoMapper, videoStatusMapper, userMapper, userFollowMapper);

    @Test
    void adminDetailPassesAllEightDatabaseCountersExactlyIncludingAbove2Pow53() {
        // visibility=1（私密）：管理员详情不做可见性过滤，私密视频的统计行同样原样返回。
        when(videoMapper.selectByVid(VID)).thenReturn(publishedVideo(VID, UID, (byte) 1));
        when(videoStatusMapper.selectByVid(VID)).thenReturn(status(
                ABOVE_EXACT_DOUBLE, Long.MAX_VALUE, 11L, 13L, 17L, 19L, 23L, 29L));
        when(userMapper.selectById(UID)).thenReturn(activeUser(UID));
        when(videoMapper.countVideoByUid(UID)).thenReturn(3);
        when(userFollowMapper.countFans(UID)).thenReturn(4);

        AdminVideoDetailVO detail = videoReviewService.getVideo(ADMIN_UID, VID);

        assertEquals(VID, detail.getVid());
        assertEquals((byte) 1, detail.getVisibility());
        assertEquals(ABOVE_EXACT_DOUBLE, detail.getPlayTimes());
        assertEquals(Long.MAX_VALUE, detail.getLikeTimes());
        assertEquals(11L, detail.getUnlikeTimes());
        assertEquals(13L, detail.getCommentTimes());
        assertEquals(17L, detail.getCoinTimes());
        assertEquals(19L, detail.getShareTimes());
        assertEquals(23L, detail.getCollectTimes());
        assertEquals(29L, detail.getDanmuTimes());
        assertEquals(3, detail.getVideoCount());
        assertEquals(4, detail.getFansCount());
        verify(videoStatusMapper).selectByVid(VID);
        // 八项计数来自同一次 selectByVid：不存在按字段补齐的第二次统计查询。
        verifyNoMoreInteractions(videoStatusMapper);
    }

    @Test
    void adminDetailWithoutStatisticsRowReturns404AndStopsBeforeAuthorAndFans() {
        when(videoMapper.selectByVid(VID)).thenReturn(publishedVideo(VID, UID, (byte) 0));
        when(videoStatusMapper.selectByVid(VID)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> videoReviewService.getVideo(ADMIN_UID, VID));

        assertEquals(404, e.getCode());
        assertEquals("视频状态为空", e.getMessage());
        // 缺行即失败：不再查作者/粉丝，也不会用 0 拼出一个看似正常的详情。
        verifyNoInteractions(userMapper, userFollowMapper);
        verify(videoMapper, never()).countVideoByUid(anyInt());
    }

    // ------------------------------------------------------------------ Feed / 列表

    @Test
    void feedReturnsTheSingleJoinedDatabaseProjectionWithoutPerVideoStatusReads() {
        LocalDateTime newest = LocalDateTime.of(2026, 10, 23, 10, 0);
        LocalDateTime older = newest.minusMinutes(1);
        when(videoMapper.selectFeed(isNull(), isNull(), eq(2), isNull(), isNull()))
                .thenReturn(List.of(
                        feedRow(VID, UID, newest, THREE_BILLION, 7L),
                        feedRow(VID - 1, UID, older, 1L, 2L)));
        CursorDTO request = new CursorDTO();
        request.setSize(1);

        CursorPageVO<VideoListItemVO> page = videoService.getVideoFeed(request);

        assertEquals(1, page.getItems().size());
        assertEquals(Boolean.TRUE, page.getHasMore());
        assertEquals(newest + "_" + VID, page.getNextCursor());
        assertEquals(THREE_BILLION, page.getItems().get(0).getPlayTimes());
        assertEquals(7L, page.getItems().get(0).getCommentTimes());
        // Feed 的统计来自 selectFeed 的同一条 JOIN 投影：不逐条补查 video_status。
        verify(videoStatusMapper, never()).selectByVid(anyInt());
    }

    @Test
    void feedPropagatesDatabaseFailureInsteadOfEmptyPage() {
        when(videoMapper.selectFeed(isNull(), isNull(), eq(16), isNull(), isNull()))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));
        CursorDTO request = new CursorDTO();
        request.setSize(15);

        assertThrows(DataAccessResourceFailureException.class,
                () -> videoService.getVideoFeed(request));
    }

    // ------------------------------------------------------------------ 用户获赞

    @Test
    void userLoveCountIsTheDatabaseAggregateAndKeepsLargeValues() {
        when(userMapper.selectById(UID)).thenReturn(activeUser(UID));
        when(userFollowMapper.countFans(UID)).thenReturn(2);
        when(userFollowMapper.countStar(UID)).thenReturn(5);
        when(videoMapper.countVideoByUid(UID)).thenReturn(7);
        when(videoStatusMapper.countLikeByUid(UID)).thenReturn(PERSONAL_LIKES);

        UserVO user = userService.getUser(null, UID);

        assertNotNull(user.getUserCount());
        assertEquals(PERSONAL_LIKES, user.getUserCount().getLoveCount());
        assertEquals(7, user.getUserCount().getVideoCount());
        verify(videoStatusMapper).countLikeByUid(UID);
        verifyNoMoreInteractions(videoStatusMapper);
    }

    @Test
    void userLoveCountPropagatesDatabaseFailureInsteadOfZero() {
        when(userMapper.selectById(UID)).thenReturn(activeUser(UID));
        when(videoStatusMapper.countLikeByUid(UID))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));

        DataAccessResourceFailureException e = assertThrows(DataAccessResourceFailureException.class,
                () -> userService.getUser(null, UID));

        assertEquals("mysql down", e.getMessage());
    }

    // ------------------------------- Redis current 只影响未接线的只读门面（不是用户读入口）

    private final RedisHashOperations hashes = mock(RedisHashOperations.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final VideoStatusVidMutex vidMutex = mock(VideoStatusVidMutex.class);
    private final VideoStatusRebuildService rebuildService = mock(VideoStatusRebuildService.class);
    private final VideoStatusServiceImpl videoStatusService = new VideoStatusServiceImpl(
            mock(VideoStatusOutboxMapper.class),
            videoStatusMapper,
            mock(JsonUtils.class),
            hashes,
            redisUtils,
            mock(VideoStatusProperties.class),
            vidMutex,
            rebuildService);

    @Test
    void cacheMissReadsTheDatabaseAndNeitherWritesBackNorStartsARebuild() {
        when(hashes.entries(RedisKeyUtils.videoStatus(VID))).thenReturn(Map.of());
        VideoStatus fromDb = status(THREE_BILLION, 1L, 0L, 0L, 0L, 0L, 0L, 0L);
        when(videoStatusMapper.selectByVid(VID)).thenReturn(fromDb);

        VideoStatus read = videoStatusService.getByVid(VID);

        assertSame(fromDb, read);
        assertEquals(THREE_BILLION, read.getPlayTimes());
        // 只有一次读：miss 不回填缓存（回填属于重建，必须由消费/重建路径显式进入）。
        verify(hashes).entries(RedisKeyUtils.videoStatus(VID));
        verifyNoMoreInteractions(hashes);
        verifyNoInteractions(redisUtils, vidMutex, rebuildService);
    }

    @Test
    void cacheMissNeverConsultsTheRebuildGateSoAHeldGateCannotBlockTheRead() {
        when(hashes.entries(RedisKeyUtils.videoStatus(VID))).thenReturn(Map.of());
        VideoStatus fromDb = status(THREE_BILLION, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        when(videoStatusMapper.selectByVid(VID)).thenReturn(fromDb);

        assertSame(fromDb, videoStatusService.getByVid(VID));

        // 读路径不看门闩、不取 vid 锁，因此“正在重建”最多让缓存暂时 miss/stale，不会阻塞或改写这次读。
        verify(hashes, never()).entries(RedisKeyUtils.videoStatusRebuildGate(VID));
        verifyNoInteractions(vidMutex, rebuildService);
    }

    @Test
    void corruptedCacheFieldFailsLoudlyInsteadOfZeroOrDatabaseFailOpen() {
        Map<String, String> corrupted = canonicalCache(VID, "not-a-number");
        when(hashes.entries(RedisKeyUtils.videoStatus(VID))).thenReturn(corrupted);

        VideoStatusCounterRangeException e = assertThrows(VideoStatusCounterRangeException.class,
                () -> videoStatusService.getByVid(VID));

        assertEquals(VideoStatusCounterRangeException.Reason.MALFORMED, e.getReason());
        // 坏字段不会被 0 覆盖，也不会静默改读 DB（那会把损坏藏起来）。
        verify(videoStatusMapper, never()).selectByVid(VID);
        verifyNoInteractions(redisUtils, vidMutex, rebuildService);
    }

    @Test
    void redisFailureOnTheReadSeamIsNotTranslatedIntoZeroCounters() {
        when(hashes.entries(RedisKeyUtils.videoStatus(VID)))
                .thenThrow(new RedisOperationException("redis down"));

        RedisOperationException e = assertThrows(RedisOperationException.class,
                () -> videoStatusService.getByVid(VID));

        assertEquals("redis down", e.getMessage());
        verify(videoStatusMapper, never()).selectByVid(VID);
        verifyNoInteractions(rebuildService);
    }

    // ------------------------------------------------------------------ 结构与 SQL 口径

    @Test
    void statsReadServicesDeclareNoRedisCollaborator() {
        for (Class<?> service : List.of(VideoServiceImpl.class, UserServiceImpl.class, VideoReviewImpl.class)) {
            for (Field field : service.getDeclaredFields()) {
                assertNotCacheType(service, field.getType());
            }
            for (Constructor<?> constructor : service.getConstructors()) {
                for (var parameter : constructor.getParameters()) {
                    assertNotCacheType(service, parameter.getType());
                }
            }
        }
    }

    private static void assertNotCacheType(Class<?> service, Class<?> type) {
        String simpleName = type.getSimpleName().toLowerCase(Locale.ROOT);
        assertFalse(simpleName.contains("redis") || simpleName.contains("redisson"),
                service.getSimpleName() + " must not depend on " + type.getName()
                        + ": T13 keeps user-facing statistics on MySQL; adding a Redis real-time read"
                        + " requires a new documented decision");
    }

    @Test
    void detailExposesSevenCountersAndFeedOnlyPlayAndComment() {
        assertEquals(Set.of("playTimes", "likeTimes", "coinTimes", "collectTimes",
                        "commentTimes", "danmuTimes", "shareTimes"),
                counterFields(VideoDetailVO.class));
        assertEquals(Set.of("playTimes", "commentTimes"), counterFields(VideoListItemVO.class));
        // unlike_times 只累计、没有用户可见读接口；把它暴露出去是产品决定，不是读路径修复。
        assertFalse(counterFields(VideoDetailVO.class).contains("unlikeTimes"));
    }

    private static Set<String> counterFields(Class<?> vo) {
        return java.util.Arrays.stream(vo.getDeclaredFields())
                .map(Field::getName)
                .filter(name -> name.endsWith("Times"))
                .collect(Collectors.toSet());
    }

    @Test
    void sqlProjectsOneDatabaseSourceWithTheDocumentedFilters() throws IOException {
        String videoSql = normalized(Files.readString(VIDEO_MAPPER, StandardCharsets.UTF_8));
        String statusSql = normalized(Files.readString(VIDEO_STATUS_MAPPER, StandardCharsets.UTF_8));

        String feed = selectBlock(videoSql, "selectFeed");
        assertTrue(feed.contains("INNER JOIN video_status vs ON vs.vid = v.vid"),
                "feed must project the video_status row through one join");
        assertTrue(feed.contains("vs.play_times AS playTimes"));
        assertTrue(feed.contains("vs.comment_times AS commentTimes"));
        assertTrue(feed.contains("WHERE v.status = 1 AND v.visibility = 0"),
                "feed is published + public only");
        assertTrue(feed.contains("AND v.deleted_at IS NULL"));
        assertFalse(feed.contains("like_times"), "feed must not start exposing more counters silently");

        String detail = selectBlock(statusSql, "selectByVid");
        assertTrue(detail.contains("FROM video_status WHERE vid = #{vid}"),
                "detail reads the statistics row by vid and nothing else");
        assertFalse(detail.contains("JOIN"), "detail must not filter the statistics row by visibility");
        assertFalse(detail.contains("deleted_at"));

        String loveCount = selectBlock(statusSql, "countLikeByUid");
        assertTrue(loveCount.contains("COALESCE(SUM(vs.like_times), 0)"),
                "personal received likes are an aggregate over like_times");
        assertTrue(loveCount.contains("INNER JOIN video v ON v.vid = vs.vid"));
        assertTrue(loveCount.contains("v.status = 1 AND v.visibility = 0"));
        assertTrue(loveCount.contains("v.deleted_at IS NULL"));
        assertFalse(loveCount.contains("unlike_times"),
                "received likes are SUM(like_times), not like-minus-unlike");
    }

    // ------------------------------------------------------------------ fixtures

    private static String normalized(String xml) {
        return xml.replaceAll("\\s+", " ");
    }

    /** One {@code <select>} body, so an assertion cannot accidentally match a neighbouring statement. */
    private static String selectBlock(String normalizedXml, String id) {
        String start = "<select id=\"" + id + "\"";
        int from = normalizedXml.indexOf(start);
        assertTrue(from >= 0, "missing select " + id);
        int end = normalizedXml.indexOf("</select>", from);
        assertTrue(end > from, "unterminated select " + id);
        return normalizedXml.substring(from, end);
    }

    private static Video publishedVideo(int vid, int uid, byte visibility) {
        Video video = new Video();
        video.setVid(vid);
        video.setUid(uid);
        video.setTitle("t13");
        video.setVisibility(visibility);
        video.setDuration(120.0);
        video.setStatus((byte) 1);
        video.setCreatedAt(LocalDateTime.of(2026, 10, 23, 9, 0));
        return video;
    }

    private static User activeUser(int uid) {
        User user = new User();
        user.setId(uid);
        user.setNickname("t13");
        user.setStatus((byte) 0);
        return user;
    }

    private static VideoStatus status(long play, long like, long unlike, long comment,
                                      long coin, long share, long collect, long danmu) {
        VideoStatus status = new VideoStatus();
        status.setVid(VID);
        status.setPlayTimes(play);
        status.setLikeTimes(like);
        status.setUnlikeTimes(unlike);
        status.setCommentTimes(comment);
        status.setCoinTimes(coin);
        status.setShareTimes(share);
        status.setCollectTimes(collect);
        status.setDanmuTimes(danmu);
        return status;
    }

    private static VideoListItemVO feedRow(int vid, int uid, LocalDateTime createdAt,
                                           long playTimes, long commentTimes) {
        VideoListItemVO item = new VideoListItemVO();
        item.setVid(vid);
        item.setUid(uid);
        item.setTitle("t13-" + vid);
        item.setDuration(60.0);
        item.setCreatedAt(createdAt);
        item.setPlayTimes(playTimes);
        item.setCommentTimes(commentTimes);
        return item;
    }

    /** A complete canonical cache hash, exactly as {@code video-status-init.lua} writes it. */
    private static Map<String, String> canonicalCache(int vid, String playTimes) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("vid", String.valueOf(vid));
        for (String field : COUNTER_FIELDS) {
            values.put(field, "0");
        }
        values.put("playTimes", playTimes);
        return values;
    }
}
