package com.feibijiubi.backend.service.impl.video.videostatus;

import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.enums.VideoStatusConsumeProcessStatus;
import com.feibijiubi.backend.event.VideoStatusDelta;
import com.feibijiubi.backend.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildSnapshot;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildSnapshotService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoStatusRebuildServiceImpl
        implements VideoStatusRebuildService {

    /** 每批 processed 证据的固定上限：有界内存、有界 pipeline，不随候选总数增长。 */
    private static final int PROCESSED_EVIDENCE_BATCH_SIZE = 256;

    @Resource(name = "videoStatusInitScript")
    private DefaultRedisScript<String> videoStatusInitScript;

    @Resource(name = "videoStatusReleaseGateScript")
    private DefaultRedisScript<Long> videoStatusReleaseGateScript;

    private final VideoStatusRebuildSnapshotService snapshotService;
    private final VideoStatusVidMutex vidMutex;
    private final RedisUtils redisUtils;

    /**
     * T05 只读观测：允许为 null（纯构造测试/未装配指标的隔离配置）。
     * 指标失败不得改变初始化/门闩释放语义。
     */
    @Autowired(required = false)
    private VideoStatusTelemetry telemetry;

    @Override
    public void ensureInitialized(Integer vid) {
        validateVid(vid);
        long startedAt = System.nanoTime();
        try {
            vidMutex.withLock(vid, () -> ensureInitializedUnderVidLock(vid));
            recordRebuild(VideoStatusTelemetry.Outcome.SUCCESS, startedAt);
        } catch (RuntimeException e) {
            recordRebuild(VideoStatusTelemetry.Outcome.FAILURE, startedAt);
            throw e;
        }
    }

    private void recordRebuild(
            VideoStatusTelemetry.Outcome outcome,
            long startedAt
    ) {
        VideoStatusTelemetry target = telemetry;
        if (target == null) {
            return;
        }
        try {
            target.record(
                    VideoStatusTelemetry.Operation.REBUILD,
                    outcome,
                    System.nanoTime() - startedAt
            );
        } catch (RuntimeException ignored) {
            // 指标自身失败不得改变重建结果。
        }
    }

    private void ensureInitializedUnderVidLock(Integer vid) {
        String gateKey = RedisKeyUtils.videoStatusRebuildGate(vid);
        // T05：token 前缀携带创建时刻（epochMillis），让门闩“停留多久”可被按需观测；
        // Lua 仍只做整串相等比较，协议不变，仍然不设 TTL。
        // 旧格式（纯 UUID）的存量门闩可被识别为“时长未知”，不会被误读。
        String ownerToken = System.currentTimeMillis() + "-"
                + UUID.randomUUID();
        try {
            // 先关闭无锁增量入口，再读快照；同一 vid 锁同时阻止刷库。
            // 不设 TTL，避免慢重建期间门闩自行失效。崩溃后下位持锁者覆盖接管。
            redisUtils.setString(gateKey, ownerToken);
            if (Boolean.TRUE.equals(
                    redisUtils.hasKey(RedisKeyUtils.videoStatus(vid))
            )) {
                return;
            }

            // load() 位于独立 Spring Bean，返回时只读事务已经结束。
            VideoStatusRebuildSnapshot snapshot = snapshotService.load(vid);
            InitialValues values = classifyConfirmedPending(snapshot);
            initializeRedis(snapshot, values, ownerToken);
        } finally {
            try {
                redisUtils.executeScript(videoStatusReleaseGateScript,
                        List.of(gateKey), ownerToken);
            } catch (RuntimeException e) {
                // 保留门闩是安全失败；后续消费进入慢路径，由新持锁者接管。
                log.warn("释放视频统计重建门闩失败，后续慢路径将接管: vid={}", vid, e);
            }
        }
    }

    /**
     * current 是 MySQL 已落库基线，加上所有已确认应用到 Redis、
     * 但仍未批量落库的事件（消费事件状态 0/1）。
     *
     * <p>T12-A：只有状态 0（RECEIVED）需要 Redis processed 证据，按固定有界批次
     * 做一次 pipeline EXISTS，替换原先每条事件的单次 RTT；状态 1
     * （REDIS_APPLIED_PENDING_FLUSH）已确认应用，不再查询 Redis。
     * 批次按快照顺序切分，逐 key 结果按位置回填，因此累计顺序与原实现一致；
     * 不额外构造全量候选 key 集合，也不允许用“存在的总数”代替逐条证据。</p>
     *
     * <p>证据查询失败（异常、条数不符、未知结果）直接上抛，绝不发布半份初始化：
     * 调用方在 {@code initializeRedis} 之前就失败，门闩由 finally 释放。</p>
     */
    private InitialValues classifyConfirmedPending(
            VideoStatusRebuildSnapshot snapshot
    ) {
        Totals pending = new Totals();
        double pendingHotScore = 0D;

        try {
            List<VideoStatusRebuildSnapshot.Candidate> candidates =
                    snapshot.candidates();
            for (int start = 0; start < candidates.size();
                 start += PROCESSED_EVIDENCE_BATCH_SIZE) {
                int end = Math.min(
                        start + PROCESSED_EVIDENCE_BATCH_SIZE,
                        candidates.size()
                );
                List<VideoStatusRebuildSnapshot.Candidate> batch =
                        candidates.subList(start, end);
                List<Boolean> evidence = loadProcessedEvidence(
                        snapshot.vid(),
                        batch
                );

                int evidenceIndex = 0;
                for (VideoStatusRebuildSnapshot.Candidate candidate : batch) {
                    boolean confirmed;
                    if (candidate.processStatus()
                            == VideoStatusConsumeProcessStatus
                            .REDIS_APPLIED_PENDING_FLUSH.getCode()) {
                        confirmed = true;
                    } else if (candidate.processStatus()
                            == VideoStatusConsumeProcessStatus.RECEIVED
                            .getCode()) {
                        confirmed = Boolean.TRUE.equals(
                                evidence.get(evidenceIndex)
                        );
                        evidenceIndex++;
                    } else {
                        confirmed = false;
                    }
                    if (!confirmed) {
                        continue;
                    }
                    pending.add(candidate.delta());
                    pendingHotScore += candidate.hotScoreDelta();
                }
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

    /**
     * 为本批状态 0 候选取 processed 证据：一批一次 pipeline EXISTS。
     * 批内没有状态 0 候选时不发起任何 Redis 请求；
     * 返回结果必须与本批 key 等长、同序且没有空值，否则抛
     * {@link RedisOperationException}（未知不等于“未应用”）。
     */
    private List<Boolean> loadProcessedEvidence(
            Integer vid,
            List<VideoStatusRebuildSnapshot.Candidate> batch
    ) {
        List<String> keys = new ArrayList<>(batch.size());
        for (VideoStatusRebuildSnapshot.Candidate candidate : batch) {
            if (candidate.processStatus()
                    == VideoStatusConsumeProcessStatus.RECEIVED.getCode()) {
                keys.add(RedisKeyUtils.processedKey(candidate.eventId()));
            }
        }
        if (keys.isEmpty()) {
            return List.of();
        }
        List<Boolean> evidence = redisUtils.hasKeys(keys);
        if (evidence == null || evidence.size() != keys.size()) {
            throw new RedisOperationException(
                    "Redis processed 证据条数与候选事件不一致，vid=" + vid
                            + "，expected=" + keys.size()
                            + "，actual="
                            + (evidence == null ? "null" : evidence.size())
            );
        }
        for (Boolean value : evidence) {
            if (value == null) {
                throw new RedisOperationException(
                        "Redis processed 证据包含空结果，vid=" + vid
                                + "，无法判定事件是否已应用"
                );
            }
        }
        return evidence;
    }

    private void initializeRedis(
            VideoStatusRebuildSnapshot snapshot,
            InitialValues values,
            String ownerToken
    ) {
        List<String> keys = List.of(
                RedisKeyUtils.videoStatus(snapshot.vid()),
                RedisKeyUtils.dirtyVideo(),
                RedisKeyUtils.feedHotVideos(),
                RedisKeyUtils.videoStatusRebuildGate(snapshot.vid())
        );

        List<String> args = new ArrayList<>(12);
        args.add(String.valueOf(snapshot.vid()));
        args.add(String.valueOf(values.hotScore()));
        values.current().appendTo(args);
        args.add(String.valueOf(values.pending().isNonZero()));
        args.add(ownerToken);

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
            // 只有符号 net 增量参与累计：允许中间先减后加，成否由 requireNonNegative 判定。
            // 溢出用 Math.addExact 显式失败，由 classifyConfirmedPending 转成可重试异常，
            // 不静默回绕成任意正数。
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
            // 基线 + 已确认待落库事件的净增量；基线非负契约在 SnapshotService 读取时已校验。
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
            // 防御深度：即使 addExact 溢出分支被绕过，也不允许负值进入 Redis 初始化。
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
