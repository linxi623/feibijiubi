package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.entity.VideoStatusOutbox;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusDelta;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.mapper.VideoStatusSequenceMapper;
import com.feibijiubi.backend.service.video.VideoStatusService;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.redis.RedisConstants;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisHashOperations;
import com.feibijiubi.backend.utils.redis.operation.RedisZSetOperations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class VideoStatusServiceImpl implements VideoStatusService {
    private final VideoStatusSequenceMapper sequenceMapper;
    private final VideoStatusOutboxMapper outboxMapper;
    private final VideoStatusMapper videoStatusMapper;
    private final JsonUtils jsonUtils;
    private final RedisHashOperations redisHashOperations;
    private final RedisZSetOperations redisZSetOperations;
    private final VideoStatusProperties properties;
    private final DefaultRedisScript<String> redisScript;
    private final RedisUtils redisUtils;
    private final VideoStatusConsumedEventMapper consumedEventMapper;

    @Override
    public ApplyResult apply(VideoStatusChangedEvent event) {
        event.validate();

        ApplyResult result = execute(event);
        if (result != ApplyResult.NEEDS_REBUILD) {
            return result;
        }

        rebuild(event.vid());
        result = execute(event);
        if (result == ApplyResult.NEEDS_REBUILD) {
            throw new BusinessException(500, "Redis 视频统计初始化失败");
        }
        return result;
    }

    private ApplyResult execute(VideoStatusChangedEvent event) {
        long ttlSeconds = properties.getRedisEventTtlDays() * 86_400L;
        String result = redisUtils.executeScript(
                redisScript,
                List.of(
                        RedisKeyUtils.videoStatus(event.vid()),
                        RedisKeyUtils.feedHotVideos(),
                        RedisKeyUtils.processedKey(event.eventId())
                ),
                event.type().redisField(),
                String.valueOf(event.delta()),
                String.valueOf(event.vid()),
                String.valueOf(event.hotScoreDelta()),
                String.valueOf(ttlSeconds),
                String.valueOf(event.aggregateSequence())
        );

        if (result == null) {
            throw new BusinessException(500, "Redis 视频统计脚本无返回值");
        }
        try {
            return ApplyResult.valueOf(result);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(500, "未知 Redis 统计结果: " + result);
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

        sequenceMapper.ensureExists(vid);
        if (sequenceMapper.increase(vid) != 1) {
            throw new BusinessException(500, "视频统计序号生成失败");
        }

        Long sequence = sequenceMapper.selectCurrent(vid);
        if (sequence == null || sequence <= 0) {
            throw new BusinessException(500, "视频统计序号数据异常");
        }

        String eventId = UUID.randomUUID().toString();
        VideoStatusChangedEvent event = new VideoStatusChangedEvent(
                eventId,
                vid,
                sequence,
                type,
                delta,
                type.calculateHotScore(delta),
                LocalDateTime.now(),
                1,
                null
        );

        VideoStatusOutbox outbox = new VideoStatusOutbox();
        outbox.setEventId(event.eventId());
        outbox.setAggregateId(event.vid());
        outbox.setAggregateSequence(event.aggregateSequence());
        outbox.setEventType(event.type().name());
        outbox.setPayload(jsonUtils.toJson(event));

        if (outboxMapper.insert(outbox) != 1) {
            throw new BusinessException(500, "视频统计事件创建失败");
        }
        return event;
    }

    @Override
    public void rebuild(Integer vid) {
        VideoStatus status = videoStatusMapper.selectByVid(vid);
        if (status == null) {
            throw new BusinessException(500, "视频统计数据不存在");
        }

        String key = RedisKeyUtils.videoStatus(vid);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("vid", String.valueOf(status.getVid()));
        values.put("playTimes", String.valueOf(status.getPlayTimes()));
        values.put("likeTimes", String.valueOf(status.getLikeTimes()));
        values.put("unlikeTimes", String.valueOf(status.getUnlikeTimes()));
        values.put("commentTimes", String.valueOf(status.getCommentTimes()));
        values.put("coinTimes", String.valueOf(status.getCoinTimes()));
        values.put("shareTimes", String.valueOf(status.getShareTimes()));
        values.put("collectTimes", String.valueOf(status.getCollectTimes()));
        values.put("danmuTimes", String.valueOf(status.getDanmuTimes()));
        values.put("lastSequence", String.valueOf(status.getAppliedSequence()));

        redisHashOperations.putAll(key, values);
        redisZSetOperations.addNewMember(
                RedisConstants.FEED_HOT_VIDEOS_PREFIX,
                String.valueOf(vid),
                calculateBaseScore(status)
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persist(
            VideoStatusChangedEvent event,
            String payloadHash
    ) {
        VideoStatusConsumedEvent consumed = new VideoStatusConsumedEvent();
        consumed.setEventId(event.eventId());
        consumed.setVid(event.vid());
        consumed.setAggregateSequence(event.aggregateSequence());
        consumed.setEventType(event.type().name());
        consumed.setDelta(event.delta());
        consumed.setPayloadHash(payloadHash);

        try {
            consumedEventMapper.insertReceived(consumed);
        } catch (DuplicateKeyException e) {
            handleDuplicate(event, payloadHash);
            return;
        }

        VideoStatusDelta delta = VideoStatusDelta.from(event);
        int rows = videoStatusMapper.applyDelta(
                event.vid(),
                event.aggregateSequence(),
                delta
        );
        if (rows != 1) {
            throw new BusinessException(
                    500,
                    "视频统计落库失败，可能存在序号缺口或负数结果"
            );
        }

        if (consumedEventMapper.markCommitted(event.eventId()) != 1) {
            throw new BusinessException(500, "视频统计消费状态更新失败");
        }
    }

    @Override
    public VideoStatus getByVid(Integer vid) {
        String key = RedisKeyUtils.videoStatus(vid);
        Map<String, String> values =
                redisHashOperations.entries(key);

        if (values.isEmpty()) {
            rebuild(vid);
            values = redisHashOperations.entries(key);
        }
        if (values.isEmpty()) {
            return videoStatusMapper.selectByVid(vid);
        }

        VideoStatus status = new VideoStatus();
        status.setVid(Integer.valueOf(String.valueOf(values.get("vid"))));
        status.setPlayTimes(parseInt(values, "playTimes"));
        status.setLikeTimes(parseInt(values, "likeTimes"));
        status.setUnlikeTimes(parseInt(values, "unlikeTimes"));
        status.setCommentTimes(parseInt(values, "commentTimes"));
        status.setCoinTimes(parseInt(values, "coinTimes"));
        status.setShareTimes(parseInt(values, "shareTimes"));
        status.setCollectTimes(parseInt(values, "collectTimes"));
        status.setDanmuTimes(parseInt(values, "danmuTimes"));
        status.setAppliedSequence(parseInt(values, "lastSequence"));
        return status;
    }

    private Integer parseInt(Map<String, String> map, String key) {
        String val = map.get(key);
        if (val == null || val.trim().isEmpty() || "null".equalsIgnoreCase(val)) {
            return 0; // 默认返回 0，如果是主键也可以返回 null
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            log.warn("解析字段 {} 失败，原始值: {}", key, val);
            return 0;
        }
    }

    private void handleDuplicate(
            VideoStatusChangedEvent event,
            String payloadHash
    ) {
        VideoStatusConsumedEvent existing =
                consumedEventMapper.selectByEventId(event.eventId());
        if (existing == null) {
            throw new BusinessException(500, "消费幂等记录查询失败");
        }

        boolean samePayload = existing.getVid().equals(event.vid())
                && existing.getAggregateSequence()
                .equals(event.aggregateSequence())
                && existing.getEventType().equals(event.type().name())
                && existing.getDelta().equals(event.delta())
                && existing.getPayloadHash().equals(payloadHash);

        if (!samePayload) {
            throw new IllegalArgumentException(
                    "相同 eventId 对应了不同统计消息"
            );
        }
        if (Integer.valueOf(1).equals(existing.getProcessStatus())) {
            return;
        }

        throw new BusinessException(
                500,
                "统计事件存在未完成消费记录，需要继续修复"
        );
    }

    private double calculateBaseScore(VideoStatus status) {
        return status.getPlayTimes()
                + status.getLikeTimes() * 1.5
                - status.getUnlikeTimes()
                + status.getCommentTimes() * 3.5
                + status.getCoinTimes() * 4.0
                + status.getShareTimes() * 2.5
                + status.getCollectTimes() * 4.0
                + status.getDanmuTimes() * 2.0;
    }
}
