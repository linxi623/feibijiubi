package com.feibijiubi.backend.service.impl.video.videostatus;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.common.RetryableMessageException;
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
    private DefaultRedisScript<String> videoStatusInitScript;

    private final VideoStatusProperties properties;
    private final VideoStatusVidMutex vidMutex;
    private final VideoStatusRebuildService videoStatusRebuildService;


    @Override
    public ApplyResult apply(VideoStatusChangedEvent event) {
        event.validate();
        return vidMutex.withLock(event.vid(), () -> applyWithRebuild(event));
    }

    private ApplyResult applyWithRebuild(VideoStatusChangedEvent event) {
        ApplyResult result = execute(event);
        if (result != ApplyResult.NEEDS_REBUILD) {
            return result;
        }

        videoStatusRebuildService.ensureInitialized(event.vid());
        result = execute(event);
        if (result == ApplyResult.NEEDS_REBUILD) {
            throw new RetryableMessageException(
                    "Redis 视频统计重建后仍缺少 current/delta，vid=" + event.vid()
            );
        }
        return result;
    }




    private ApplyResult execute(VideoStatusChangedEvent event) {
        List<String> keys = List.of(
                RedisKeyUtils.videoStatus(event.vid()),
                RedisKeyUtils.videoStatusDelta(event.vid()),
                RedisKeyUtils.dirtyVideo(),
                RedisKeyUtils.processedKey(event.eventId()),
                RedisKeyUtils.feedHotVideos()
        );

        String result = redisUtils.executeScript(
                videoStatusInitScript,
                keys,
                event.type().redisField(),
                event.type().deltaField(),
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
        status.setVid(parseInt(values, "vid"));
        status.setPlayTimes(parseInt(values, "playTimes"));
        status.setLikeTimes(parseInt(values, "likeTimes"));
        status.setUnlikeTimes(parseInt(values, "unlikeTimes"));
        status.setCommentTimes(parseInt(values, "commentTimes"));
        status.setCoinTimes(parseInt(values, "coinTimes"));
        status.setShareTimes(parseInt(values, "shareTimes"));
        status.setCollectTimes(parseInt(values, "collectTimes"));
        status.setDanmuTimes(parseInt(values, "danmuTimes"));
        return status;
    }

    private Integer parseInt(Map<String, String> map, String key) {
        String value = map.get(key);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            log.warn("解析 Redis 视频统计字段失败: field={}, value={}", key, value);
            return 0;
        }
    }
}
