package com.feibijiubi.backend.service.impl.video.videostatus;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.common.VideoStatusCounterRange;
import com.feibijiubi.backend.common.VideoStatusCounterRangeException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.entity.VideoStatusOutbox;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisHashOperations;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 视频统计的兼容门面。
 *
 * <p>按当前项目结构保留事件创建、Redis 实时聚合和查询入口；消费登记与
 * 批量刷库分别由独立事务服务负责。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoStatusServiceImpl implements VideoStatusService {

    private final VideoStatusOutboxMapper outboxMapper;
    private final VideoStatusMapper videoStatusMapper;
    private final JsonUtils jsonUtils;
    private final RedisHashOperations redisHashOperations;
    private final AtomicLong redisQueryMissCount = new AtomicLong();
    private final RedisUtils redisUtils;

    @Resource(name = "videoStatusIncrementScript")
    private DefaultRedisScript<String> videoStatusIncrementScript;

    private final VideoStatusProperties properties;
    private final VideoStatusVidMutex vidMutex;
    private final VideoStatusRebuildService videoStatusRebuildService;


    @Override
    public ApplyResult apply(VideoStatusChangedEvent event) {
        event.validate();
        // 正常路径仅执行一次 Lua；只有缓存缺失/重建门闩关闭时才进入加锁慢路径。
        return applyWithRebuild(event);
    }

    private ApplyResult applyWithRebuild(VideoStatusChangedEvent event) {
        ApplyResult result = execute(event);
        if (result != ApplyResult.NEEDS_REBUILD) {
            return result;
        }

        return vidMutex.withLock(event.vid(), () -> {
            // 等锁期间其他消费者可能已完成重建；先重试，避免重复设置门闩。
            ApplyResult retry = execute(event);
            if (retry != ApplyResult.NEEDS_REBUILD) {
                return retry;
            }
            // ensureInitialized 使用同一把可重入锁。
            videoStatusRebuildService.ensureInitialized(event.vid());
            retry = execute(event);
            if (retry == ApplyResult.NEEDS_REBUILD) {
                throw new RetryableMessageException(
                        "Redis 视频统计重建后仍缺少 current，vid=" + event.vid()
                );
            }
            return retry;
        });
    }




    private ApplyResult execute(VideoStatusChangedEvent event) {
        List<String> keys = List.of(
                RedisKeyUtils.videoStatus(event.vid()),
                RedisKeyUtils.dirtyVideo(),
                RedisKeyUtils.processedKey(event.eventId()),
                RedisKeyUtils.feedHotVideos(),
                RedisKeyUtils.videoStatusRebuildGate(event.vid())
        );

        String result = redisUtils.executeScript(
                videoStatusIncrementScript,
                keys,
                event.type().redisField(),
                String.valueOf(event.delta()),
                String.valueOf(event.vid()),
                String.valueOf(event.hotScoreDelta()),
                String.valueOf(Duration.ofDays(
                        properties.getRedisEventTtlDays()
                ).toSeconds())
        );

        if (result == null) {
            throw new RetryableMessageException("Redis 视频统计聚合脚本无返回值");
        }
        try {
            // COUNTER_OUT_OF_RANGE 是合法的枚举值，会原样返回给消费者分类处理；
            // 这里只兜底无法识别的返回码。
            return ApplyResult.valueOf(result);
        } catch (IllegalArgumentException e) {
            throw new RetryableMessageException(
                    "未知 Redis 视频统计聚合结果: " + result,
                    e
            );
        }
    }


    @Override
    @Transactional(
            propagation = Propagation.MANDATORY,
            rollbackFor = Exception.class
    )
    public VideoStatusChangedEvent createEvent(
            Integer vid,
            VideoStatusEventType type,
            long delta
    ) {
        if (vid == null || vid <= 0 || type == null || delta == 0) {
            throw new BusinessException(400, "视频统计事件参数不合法");
        }

        VideoStatusChangedEvent event = new VideoStatusChangedEvent(
                UUID.randomUUID().toString(),
                vid,
                type,
                delta,
                type.calculateHotScore(delta),
                LocalDateTime.now(),
                2,
                null
        );

        VideoStatusOutbox outbox = new VideoStatusOutbox();
        outbox.setEventId(event.eventId());
        outbox.setAggregateId(event.vid());
        outbox.setEventType(event.type().name());
        outbox.setPayload(jsonUtils.toJson(event));

        if (outboxMapper.insert(outbox) != 1) {
            throw new BusinessException(500, "视频统计事件创建失败");
        }
        return event;
    }


    @Override
    public VideoStatus getByVid(Integer vid) {
        Map<String, String> values = redisHashOperations.entries(
                RedisKeyUtils.videoStatus(vid)
        );
        if (values.isEmpty()) {
            long misses = redisQueryMissCount.incrementAndGet();
            log.debug(
                    "video_status_query_redis_miss vid={}, totalMisses={}",
                    vid,
                    misses
            );
            return videoStatusMapper.selectByVid(vid);
        }

        VideoStatus status = new VideoStatus();
        status.setVid(parseVid(values, "vid", vid));
        status.setPlayTimes(parseCount(values, "playTimes", vid));
        status.setLikeTimes(parseCount(values, "likeTimes", vid));
        status.setUnlikeTimes(parseCount(values, "unlikeTimes", vid));
        status.setCommentTimes(parseCount(values, "commentTimes", vid));
        status.setCoinTimes(parseCount(values, "coinTimes", vid));
        status.setShareTimes(parseCount(values, "shareTimes", vid));
        status.setCollectTimes(parseCount(values, "collectTimes", vid));
        status.setDanmuTimes(parseCount(values, "danmuTimes", vid));
        return status;
    }

    /**
     * T02: Redis 解析不得静默返回 0。缺失/非十进制/负数/超 Long 上限都会
     * 抛出 {@link VideoStatusCounterRangeException}，由调用方显式处理。
     */
    private Long parseCount(
            Map<String, String> map,
            String key,
            Integer vid
    ) {
        return VideoStatusCounterRange.parse(
                map.get(key),
                "videoStatus[" + vid + "]." + key
        );
    }

    private Integer parseVid(
            Map<String, String> map,
            String key,
            Integer vid
    ) {
        long parsed = VideoStatusCounterRange.parse(
                map.get(key),
                "videoStatus[" + vid + "]." + key
        );
        if (parsed > Integer.MAX_VALUE) {
            throw new VideoStatusCounterRangeException(
                    "videoStatus[" + vid + "]." + key,
                    VideoStatusCounterRangeException.Reason.OVERFLOW,
                    "Redis vid 超出 Integer 范围: value=" + parsed
            );
        }
        // 缓存 Hash 必须属于被查询的 vid：若 vid 字段指向别的视频，说明 key 与内容
        // 已经错位，继续返回会把 A 的统计冒充成 B 的统计。
        if (parsed != vid.longValue()) {
            throw new IllegalStateException(
                    "Redis 视频统计 vid 与请求不一致: field=videoStatus[" + vid + "]." + key
                            + ", expected=" + vid
                            + ", actual=" + parsed
            );
        }
        return (int) parsed;
    }
}
