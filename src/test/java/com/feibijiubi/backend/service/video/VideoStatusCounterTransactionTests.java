package com.feibijiubi.backend.service.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.user.converter.UserConverter;
import com.feibijiubi.backend.user.mapper.UserFollowMapper;
import com.feibijiubi.backend.user.mapper.UserMapper;
import com.feibijiubi.backend.interaction.mapper.UserVideoMapper;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.user.service.impl.UserServiceImpl;
import com.feibijiubi.backend.interaction.service.UserVideoService;
import com.feibijiubi.backend.interaction.service.impl.UserVideoServiceImpl;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusBatchFlushServiceImpl;
import com.feibijiubi.backend.user.service.UserService;
import com.feibijiubi.backend.video.service.PublishedVideoGuard;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService;
import com.feibijiubi.backend.user.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * T02 事务验收补测：只补测试，不改业务代码。
 *
 * <p>三项证据：</p>
 * <ol>
 *   <li><b>拆批（flush）</b>：真实的 {@code VideoStatusBatchFlushServiceImpl}（真实 Spring
 *       {@code @Transactional} 代理）+ 真实 {@code VideoStatusMapper} SQL 写计数，仅把
 *       {@code VideoStatusConsumedEventMapper#markFlushed} 换成"抛异常 / 返回 0"。目的是证明
 *       {@code applyBatchDelta} 已经写入之后 {@code markFlushed} 失败，两者一起回滚：
 *       计数回到基线，事件仍是 {@code PENDING_FLUSH(1)}，没有 {@code FLUSHED(2)}。</li>
 *   <li><b>拆批（业务事务）</b>：真实 {@link UserVideoServiceImpl} + 真实关系/计数 mapper，
 *       关系先更新成功、随后计数守卫影响 0 行抛 {@link BusinessException}，
 *       整个事务回滚，{@code user_video.liked} 保持原值。同步路径（async=false），
 *       不写 outbox、不碰 MQ。</li>
 *   <li><b>个人获赞聚合</b>：{@code countLikeByUid}（3e9）→ {@link UserConverter} →
 *       {@code UserVO.userCount.loveCount} 精确，且只统计已发布公开视频。</li>
 * </ol>
 *
 * <p>隔离：复用 {@link VideoStatusCounterIsolatedTests} 的 {@code TestApplication} 与
 * {@code IsolationGuard}，只允许 {@code compose.test.yaml} 的 MySQL 13307 / Redis 16379
 * （Redis 只做配置校验，本类不使用），不启动 RabbitMQ 监听。</p>
 *
 * <p>运行：{@code ./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusCounterTransactionTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = {VideoStatusCounterIsolatedTests.TestApplication.class,
        VideoStatusCounterTransactionTests.TransactionalTestBeans.class},
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                // 同步路径：不写 outbox、不进 MQ。内联属性优先级高于 video-status-isolated.properties。
                "app.video-status.async-enabled=false",
                "app.video-status.scheduling-enabled=false",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusCounterIsolatedTests.IsolationGuard.class)
class VideoStatusCounterTransactionTests {

    /**
     * 真实 mapper + 真实事务代理的 bean 定义。
     *
     * <p>只有 {@code VideoStatusService}（outbox / MQ 入口）以 {@code null} 传入，
     * 因为 {@code asyncEnabled=false} 时 {@code recordStatus} 走同步分支，永远不会访问它；
     * 一旦有人把 async 打开，本类会因 NPE 立即失败，而不是偷偷写 outbox。</p>
     *
     * <p>T15 第二阶段：播放/分享入口现在由 {@code VideoInteractionAdmissionService} 编排，本类
     * 不测它，所以传 {@code null}（一样是“谁调谁 NPE”的闸门，而不是静默绕开准入）。
     * 但 {@code PublishedVideoGuard} 必须是真的：点赞/收藏/进度的 {@code validateVideo} 已经委派给它。</p>
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VideoStatusProperties.class)
    // R2：UserMapper 已迁到 com.feibijiubi.backend.user.mapper，不再落在 TestApplication 的
    // @MapperScan("com.feibijiubi.backend.mapper") 范围内，这里显式补扫该包。
    // R3：VideoMapper 迁到 com.feibijiubi.backend.video.mapper，UserVideoMapper 迁到
    // com.feibijiubi.backend.interaction.mapper，同样追加（旧范围不动）。
    @MapperScan({"com.feibijiubi.backend.user.mapper", "com.feibijiubi.backend.video.mapper",
            "com.feibijiubi.backend.interaction.mapper"})
    static class TransactionalTestBeans {

        @Bean
        UserVideoService userVideoService(VideoMapper videoMapper,
                                          VideoStatusMapper videoStatusMapper,
                                          UserVideoMapper userVideoMapper,
                                          UserMapper userMapper,
                                          VideoStatusProperties videoStatusProperties) {
            return new UserVideoServiceImpl(videoStatusMapper, userVideoMapper,
                    userMapper, null, videoStatusProperties,
                    null, new PublishedVideoGuard(videoMapper));
        }

        @Bean
        UserService userService(UserMapper userMapper,
                                UserFollowMapper userFollowMapper,
                                VideoMapper videoMapper,
                                VideoStatusMapper videoStatusMapper) {
            // FileStorageService 只在头像/背景上传时使用，getUser 链路不访问。
            return new UserServiceImpl(userMapper, null, userFollowMapper, videoMapper, videoStatusMapper);
        }

        /**
         * 除 {@code markFlushed} 外全部委派给真实 MyBatis mapper（真实 SQL、真实事务同步），
         * 每个用例再给 {@code markFlushed} 打"抛异常 / 返回 0"的桩。
         */
        @Bean("failingFlushConsumedEventMapper")
        VideoStatusConsumedEventMapper failingFlushConsumedEventMapper(
                @Qualifier("videoStatusConsumedEventMapper") VideoStatusConsumedEventMapper realMapper) {
            return mock(VideoStatusConsumedEventMapper.class, AdditionalAnswers.delegatesTo(realMapper));
        }

        @Bean
        VideoStatusBatchFlushService videoStatusBatchFlushService(
                @Qualifier("failingFlushConsumedEventMapper") VideoStatusConsumedEventMapper consumedEventMapper,
                VideoStatusMapper videoStatusMapper) {
            return new VideoStatusBatchFlushServiceImpl(consumedEventMapper, videoStatusMapper);
        }
    }

    private static final long THREE_BILLION = 3_000_000_000L;

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    VideoStatusMapper videoStatusMapper;
    @Autowired
    VideoStatusBatchFlushService batchFlushService;
    @Autowired
    UserVideoService userVideoService;
    @Autowired
    UserService userService;

    @Autowired
    @Qualifier("failingFlushConsumedEventMapper")
    VideoStatusConsumedEventMapper consumedEventMapper;

    int vid;
    int authorUid;

    @BeforeEach
    void seed() {
        // reset 只清调用记录与桩，mock 的默认答案（委派真实 mapper）保留。
        Mockito.reset(consumedEventMapper);

        authorUid = insertUser("t02-tx");
        vid = insertVideo(authorUid, 1, 0);
        jdbc.update("INSERT INTO video_status(vid) VALUES (?)", vid);
    }

    @AfterEach
    void assertNoOutboxSideEffect() {
        // 同步路径不应产生任何 outbox 事件（异步/MQ 未参与）。
        Integer outbox = jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_outbox WHERE aggregate_id = ?", Integer.class, vid);
        assertEquals(0, outbox, "sync path must not publish outbox events");
    }

    // ------------------------------------------------------------------ seeding

    private int insertUser(String label) {
        String username = "t" + label + UUID.randomUUID().toString().replace("-", "");
        if (username.length() > 50) {
            username = username.substring(0, 50);
        }
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')", username);
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
    }

    private int insertVideo(int uid, int status, int visibility) {
        jdbc.update("INSERT INTO video(uid,title,status,visibility,duration,mc_id,sc_id,"
                        + "cover_url,cover_key,video_url,video_key) "
                        + "VALUES (?, 't02-tx', ?, ?, 10, 'test', 'test', '', '', '', '')",
                uid, status, visibility);
        return jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
    }

    private long insertPendingPlayEvent(long delta) {
        String eventId = "t02-tx-" + UUID.randomUUID();
        jdbc.update("INSERT INTO video_status_consumed_event("
                        + "event_id, vid, event_type, delta, payload, payload_hash, process_status, redis_applied_at) "
                        + "VALUES (?, ?, 'PLAY', ?, '{}', ?, 1, NOW(3))",
                eventId, vid, delta, "0".repeat(64));
        return jdbc.queryForObject(
                "SELECT id FROM video_status_consumed_event WHERE event_id = ?", Long.class, eventId);
    }

    private long playTimes() {
        return jdbc.queryForObject("SELECT play_times FROM video_status WHERE vid = ?", Long.class, vid);
    }

    private long likeTimes() {
        return jdbc.queryForObject("SELECT like_times FROM video_status WHERE vid = ?", Long.class, vid);
    }

    private int processStatus(long id) {
        return jdbc.queryForObject(
                "SELECT process_status FROM video_status_consumed_event WHERE id = ?", Integer.class, id);
    }

    private String flushedAt(long id) {
        return jdbc.queryForObject(
                "SELECT flushed_at FROM video_status_consumed_event WHERE id = ?", String.class, id);
    }

    private int flushedCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_consumed_event WHERE vid = ? AND process_status = 2",
                Integer.class, vid);
    }

    private boolean likedOf(int uid) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT liked FROM user_video WHERE uid = ? AND vid = ?", Boolean.class, uid, vid));
    }

    private String likedAtOf(int uid) {
        return jdbc.queryForObject(
                "SELECT liked_at FROM user_video WHERE uid = ? AND vid = ?", String.class, uid, vid);
    }

    // ------------------------------------------------------- 1) flush 拆批回滚证据

    /**
     * {@code applyBatchDelta} 已经真实写入（同一事务内可读到新值），随后
     * {@code markFlushed} 抛异常时，计数与事件状态必须一起回滚。
     */
    @Test
    void flushRollsBackAppliedDeltaWhenMarkFlushedThrows() {
        long baseline = 5L;
        jdbc.update("UPDATE video_status SET play_times = ? WHERE vid = ?", baseline, vid);
        long first = insertPendingPlayEvent(1L);
        long second = insertPendingPlayEvent(2L);

        doAnswer(invocation -> {
            // 真实 SQL 的 applyBatchDelta 已经在同一事务里生效，才轮到 markFlushed
            assertEquals(baseline + 3L, playTimes(),
                    "applyBatchDelta must already be visible inside the same MySQL transaction");
            assertEquals(baseline + 3L, videoStatusMapper.selectByVid(vid).getPlayTimes(),
                    "real mapper read inside the transaction must see the applied batch delta");
            throw new IllegalStateException("T02 injected markFlushed failure");
        }).when(consumedEventMapper).markFlushed(anyList());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> batchFlushService.flushOneVideo(vid, 10));
        assertEquals("T02 injected markFlushed failure", thrown.getMessage());

        // 回滚证据：计数回到基线，两条事件都还是 PENDING_FLUSH(1)，没有 FLUSHED(2)
        assertEquals(baseline, playTimes(), "applyBatchDelta must be rolled back");
        assertEquals(1, processStatus(first));
        assertEquals(1, processStatus(second));
        assertNull(flushedAt(first));
        assertNull(flushedAt(second));
        assertEquals(0, flushedCount());
    }

    /** 同一条路径的另一种失败形态：{@code markFlushed} 返回 0 行（数量不一致）也必须回滚计数。 */
    @Test
    void flushRollsBackAppliedDeltaWhenMarkFlushedCountMismatch() {
        long baseline = 7L;
        jdbc.update("UPDATE video_status SET play_times = ? WHERE vid = ?", baseline, vid);
        long only = insertPendingPlayEvent(4L);

        doAnswer(invocation -> {
            assertEquals(baseline + 4L, playTimes(),
                    "applyBatchDelta must already be visible before markFlushed is evaluated");
            return 0;
        }).when(consumedEventMapper).markFlushed(anyList());

        RetryableMessageException thrown = assertThrows(RetryableMessageException.class,
                () -> batchFlushService.flushOneVideo(vid, 10));
        assertTrue(thrown.getMessage().contains("FLUSHED"), thrown.getMessage());

        assertEquals(baseline, playTimes(), "applyBatchDelta must be rolled back");
        assertEquals(1, processStatus(only));
        assertNull(flushedAt(only));
        assertEquals(0, flushedCount());
    }

    /** 正常路径对照：真实 markFlushed 生效时两个写入都提交，证明上面的 0 行不是别的原因造成的。 */
    @Test
    void flushCommitsBothWritesOnTheRealMarkFlushed() {
        long baseline = 2L;
        jdbc.update("UPDATE video_status SET play_times = ? WHERE vid = ?", baseline, vid);
        long first = insertPendingPlayEvent(1L);
        long second = insertPendingPlayEvent(2L);

        batchFlushService.flushOneVideo(vid, 10);

        assertEquals(baseline + 3L, playTimes());
        assertEquals(2, processStatus(first));
        assertEquals(2, processStatus(second));
        assertEquals(2, flushedCount());
    }

    // ------------------------------------ 2) 关系已更新 + 计数守卫拒绝 -> 关系回滚

    /** liked false -> true，但 like_times 已是 Long.MAX_VALUE：关系更新必须跟着回滚。 */
    @Test
    void likeRelationshipRollsBackWhenCounterRejectsAtUpperBound() {
        int uid = insertUser("t02-tx-like");
        jdbc.update("UPDATE video_status SET like_times = ? WHERE vid = ?", Long.MAX_VALUE, vid);
        jdbc.update("INSERT INTO user_video(vid, uid) VALUES (?, ?)", vid, uid);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> userVideoService.recordLike(uid, vid, true, true));
        assertEquals(500, thrown.getCode());

        assertFalse(likedOf(uid), "user_video.liked must be rolled back to false");
        assertNull(likedAtOf(uid), "user_video.liked_at must be rolled back to NULL");
        assertEquals(Long.MAX_VALUE, likeTimes(), "counter must stay at Long.MAX_VALUE");
    }

    /** liked true -> false，但 like_times 基线是 0：非法取消同样必须连关系一起回滚。 */
    @Test
    void unlikeRelationshipRollsBackWhenCounterRejectsAtZeroBaseline() {
        int uid = insertUser("t02-tx-unlike");
        jdbc.update("UPDATE video_status SET like_times = 0 WHERE vid = ?", vid);
        jdbc.update("INSERT INTO user_video(vid, uid, liked) VALUES (?, ?, 1)", vid, uid);

        BusinessException thrown = assertThrows(BusinessException.class,
                () -> userVideoService.recordLike(uid, vid, true, false));
        assertEquals(500, thrown.getCode());

        assertTrue(likedOf(uid), "user_video.liked must stay true after rollback");
        assertEquals(0L, likeTimes(), "counter must stay at 0 without underflow");
    }

    // ------------------------------------- 3) 个人获赞聚合 3e9 精确（仅已发布公开视频）

    @Test
    void personalLikeAggregateKeepsThreeBillionThroughMapperConverterAndVo() {
        int author = insertUser("t02-tx-aggregate");
        int publishedA = insertVideo(author, 1, 0);
        int publishedB = insertVideo(author, 1, 0);
        int unpublished = insertVideo(author, 0, 0);
        int privateVideo = insertVideo(author, 1, 1);

        jdbc.update("INSERT INTO video_status(vid, like_times) VALUES (?, ?)", publishedA, 2_000_000_000L);
        jdbc.update("INSERT INTO video_status(vid, like_times) VALUES (?, ?)", publishedB, 1_000_000_000L);
        // 以下两条故意写入超过 Integer 的数值，用来证明"未发布 / 私密"完全不参与聚合
        jdbc.update("INSERT INTO video_status(vid, like_times) VALUES (?, ?)", unpublished, 5_000_000_000L);
        jdbc.update("INSERT INTO video_status(vid, like_times) VALUES (?, ?)", privateVideo, 4_000_000_000L);

        assertEquals(THREE_BILLION, videoStatusMapper.countLikeByUid(author),
                "only published + public videos may be aggregated");

        UserVO vo = userService.getUser(null, author);
        assertEquals(THREE_BILLION, vo.getUserCount().getLoveCount());
        assertEquals(2, vo.getUserCount().getVideoCount());
        assertEquals(0, vo.getUserCount().getFansCount());
        assertEquals(0, vo.getUserCount().getStarCount());

        assertEquals(THREE_BILLION, UserConverter.toUserCountVO(0, 0,
                videoStatusMapper.countLikeByUid(author), 2).getLoveCount());
    }
}
