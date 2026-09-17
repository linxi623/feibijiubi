package com.feibijiubi.backend.service.impl.video.videostatus;

import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.enums.VideoStatusConsumeProcessStatus;
import com.feibijiubi.backend.event.VideoStatusDelta;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildSnapshot;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildSnapshotService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoStatusRebuildServiceImpl
        implements VideoStatusRebuildService {

    @Resource(name = "videoStatusInitScript")
    private DefaultRedisScript<String> videoStatusInitScript;

    private final VideoStatusRebuildSnapshotService snapshotService;
    private final VideoStatusVidMutex vidMutex;
    private final RedisUtils redisUtils;

    @Override
    public void ensureInitialized(Integer vid) {
        validateVid(vid);
        vidMutex.withLock(vid, () -> ensureInitializedUnderVidLock(vid));
    }

    private void ensureInitializedUnderVidLock(Integer vid) {
        if (Boolean.TRUE.equals(
                redisUtils.hasKey(RedisKeyUtils.videoStatus(vid))
        )) {
            return;
        }

        // load() 位于独立 Spring Bean，返回时只读事务已经结束。
        VideoStatusRebuildSnapshot snapshot = snapshotService.load(vid);
        InitialValues values = classifyConfirmedPending(snapshot);
        initializeRedis(snapshot, values);
    }

    /**
     * current 是 MySQL 已落库基线，加上所有已确认应用到 Redis、
     * 但仍未批量落库的事件（消费事件状态 0/1）。
     */
    private InitialValues classifyConfirmedPending(
            VideoStatusRebuildSnapshot snapshot
    ) {
        Totals pending = new Totals();
        double pendingHotScore = 0D;

        try {
            for (VideoStatusRebuildSnapshot.Candidate candidate
                    : snapshot.candidates()) {
                boolean confirmed =
                        candidate.processStatus()
                                == VideoStatusConsumeProcessStatus
                                .REDIS_APPLIED_PENDING_FLUSH.getCode()
                                || (candidate.processStatus()
                                == VideoStatusConsumeProcessStatus.RECEIVED
                                .getCode()
                                && Boolean.TRUE.equals(redisUtils.hasKey(
                                RedisKeyUtils.processedKey(
                                        candidate.eventId()
                                )
                        )));
                if (!confirmed) {
                    continue;
                }
                pending.add(candidate.delta());
                pendingHotScore += candidate.hotScoreDelta();
            }

            Totals current = Totals.from(snapshot);
            current.add(pending);
            current.requireNonNegative(snapshot.vid());

            double hotScore = Totals.hotScoreOf(snapshot) + pendingHotScore;
            if (!Double.isFinite(hotScore)) {
                throw new ArithmeticException("hotScore overflow");
            }
            return new InitialValues(current, pending, hotScore);
        } catch (ArithmeticException e) {
            throw new RetryableMessageException(
                    "Redis 视频统计重建聚合溢出，vid=" + snapshot.vid(),
                    e
            );
        }
    }

    private void initializeRedis(
            VideoStatusRebuildSnapshot snapshot,
            InitialValues values
    ) {
        List<String> keys = List.of(
                RedisKeyUtils.videoStatus(snapshot.vid()),
                RedisKeyUtils.dirtyVideo(),
                RedisKeyUtils.feedHotVideos()
        );

        List<String> args = new ArrayList<>(11);
        args.add(String.valueOf(snapshot.vid()));
        args.add(String.valueOf(values.hotScore()));
        values.current().appendTo(args);
        args.add(String.valueOf(values.pending().isNonZero()));

        String result = redisUtils.executeScript(
                videoStatusInitScript,
                keys,
                args.toArray()
        );
        if ("INITIALIZED".equals(result)) {
            return;
        }
        if ("ALREADY_INITIALIZED".equals(result)) {
            if (Boolean.TRUE.equals(
                    redisUtils.hasKey(RedisKeyUtils.videoStatus(snapshot.vid()))
            )) {
                return;
            }
            throw new RetryableMessageException(
                    "Redis 初始化返回 ALREADY_INITIALIZED，但 current "
                            + "不完整，vid=" + snapshot.vid()
            );
        }
        throw new RetryableMessageException(
                "Redis 视频统计初始化失败，vid=" + snapshot.vid()
                        + "，result=" + result
        );
    }

    private void validateVid(Integer vid) {
        if (vid == null || vid <= 0) {
            throw new IllegalArgumentException("vid 必须大于 0");
        }
    }

    private record InitialValues(
            Totals current,
            Totals pending,
            double hotScore
    ) {
    }

    private static final class Totals {
        private long play;
        private long like;
        private long unlike;
        private long comment;
        private long coin;
        private long share;
        private long collect;
        private long danmu;

        private static Totals from(VideoStatusRebuildSnapshot snapshot) {
            Totals result = new Totals();
            result.play = snapshot.playTimes();
            result.like = snapshot.likeTimes();
            result.unlike = snapshot.unlikeTimes();
            result.comment = snapshot.commentTimes();
            result.coin = snapshot.coinTimes();
            result.share = snapshot.shareTimes();
            result.collect = snapshot.collectTimes();
            result.danmu = snapshot.danmuTimes();
            return result;
        }

        private static double hotScoreOf(VideoStatusRebuildSnapshot snapshot) {
            return snapshot.playTimes()
                    + snapshot.likeTimes() * 1.5D
                    - snapshot.unlikeTimes()
                    + snapshot.commentTimes() * 3.5D
                    + snapshot.coinTimes() * 4D
                    + snapshot.shareTimes() * 2.5D
                    + snapshot.collectTimes() * 4D
                    + snapshot.danmuTimes() * 2D;
        }

        private void add(VideoStatusDelta delta) {
            play = Math.addExact(play, delta.playDelta());
            like = Math.addExact(like, delta.likeDelta());
            unlike = Math.addExact(unlike, delta.unlikeDelta());
            comment = Math.addExact(comment, delta.commentDelta());
            coin = Math.addExact(coin, delta.coinDelta());
            share = Math.addExact(share, delta.shareDelta());
            collect = Math.addExact(collect, delta.collectDelta());
            danmu = Math.addExact(danmu, delta.danmuDelta());
        }

        private void add(Totals other) {
            play = Math.addExact(play, other.play);
            like = Math.addExact(like, other.like);
            unlike = Math.addExact(unlike, other.unlike);
            comment = Math.addExact(comment, other.comment);
            coin = Math.addExact(coin, other.coin);
            share = Math.addExact(share, other.share);
            collect = Math.addExact(collect, other.collect);
            danmu = Math.addExact(danmu, other.danmu);
        }

        private void requireNonNegative(Integer vid) {
            if (play < 0 || like < 0 || unlike < 0 || comment < 0
                    || coin < 0 || share < 0 || collect < 0 || danmu < 0) {
                throw new ArithmeticException(
                        "negative rebuilt video status, vid=" + vid
                );
            }
        }

        private boolean isNonZero() {
            return play != 0
                    || like != 0
                    || unlike != 0
                    || comment != 0
                    || coin != 0
                    || share != 0
                    || collect != 0
                    || danmu != 0;
        }

        private void appendTo(List<String> target) {
            target.add(String.valueOf(play));
            target.add(String.valueOf(like));
            target.add(String.valueOf(unlike));
            target.add(String.valueOf(comment));
            target.add(String.valueOf(coin));
            target.add(String.valueOf(share));
            target.add(String.valueOf(collect));
            target.add(String.valueOf(danmu));
        }
    }
}
