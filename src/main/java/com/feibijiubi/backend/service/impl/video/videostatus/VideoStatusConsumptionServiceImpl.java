package com.feibijiubi.backend.service.impl.video.videostatus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.NonRetryableMessageException;
import com.feibijiubi.backend.common.RepairRequiredMessageException;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.enums.RegistrationResult;
import com.feibijiubi.backend.enums.VideoStatusConsumeProcessStatus;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusConsumptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoStatusConsumptionServiceImpl
        implements VideoStatusConsumptionService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final ObjectMapper objectMapper;
    private final VideoStatusConsumedEventMapper consumedEventMapper;
    private final VideoStatusProperties properties;

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW, // 不管外层是否有事务，这个方法都独立开启新事务，插入记录后立即提交（或回滚）
            rollbackFor = Exception.class,
            noRollbackFor = RepairRequiredMessageException.class
    )
    public RegistrationResult register(
            VideoStatusChangedEvent event,
            String semanticPayloadHash
    ) {
        event.validate();
        validatePayloadHash(semanticPayloadHash);

        VideoStatusConsumedEvent received = new VideoStatusConsumedEvent();
        LocalDateTime now = LocalDateTime.now();
        received.setEventId(event.eventId());
        received.setVid(event.vid());
        received.setEventType(event.type().name());
        received.setDelta(event.delta());
        received.setPayloadHash(semanticPayloadHash);
        received.setPayload(serialize(event));
        received.setProcessStatus(
                VideoStatusConsumeProcessStatus.RECEIVED.getCode()
        );
        received.setConsumerRetryCount(0);
        received.setLastAttemptAt(now);
        received.setConsumedAt(now);

        try {
            if (consumedEventMapper.insertReceived(received) != 1) {
                throw new RetryableMessageException(
                        "视频统计消费事件登记失败，eventId=" + event.eventId()
                );
            }
            return RegistrationResult.NEEDS_REDIS_APPLY;
        } catch (DuplicateKeyException duplicateKeyException) {
            return handleDuplicate(event, semanticPayloadHash);
        }
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void markRedisApplied(String eventId) {
        if (consumedEventMapper.markRedisApplied(eventId) == 1) {
            return;
        }

        VideoStatusConsumedEvent existing =
                consumedEventMapper.selectByEventId(eventId);
        if (existing == null) {
            throw new RetryableMessageException(
                    "Redis 已应用但消费事件不存在，eventId=" + eventId
            );
        }

        Integer status = existing.getProcessStatus();
        if (Objects.equals(status,
                VideoStatusConsumeProcessStatus.REDIS_APPLIED_PENDING_FLUSH
                        .getCode())
                || Objects.equals(status,
                VideoStatusConsumeProcessStatus.FLUSHED.getCode())) {
            return;
        }
        if (Objects.equals(status,
                VideoStatusConsumeProcessStatus.RECEIVED.getCode())) {
            throw new RetryableMessageException(
                    "消费事件仍为 RECEIVED，稍后重试标记 Redis 已应用，eventId="
                            + eventId
            );
        }
        if (Objects.equals(status,
                VideoStatusConsumeProcessStatus.REPAIR_REQUIRED.getCode())) {
            throw new RepairRequiredMessageException(
                    "消费事件已进入人工修复，eventId=" + eventId,
                    true
            );
        }
        throw new NonRetryableMessageException(
                "消费事件存在未知处理状态，eventId=" + eventId
                        + "，status=" + status
        );
    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void recordConsumerFailure(
            String eventId,
            int attempt,
            String lastError
    ) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt 不能小于 0");
        }
        int updated = consumedEventMapper.recordConsumerFailure(
                eventId,
                attempt,
                truncate(lastError)
        );
        if (updated == 0) {
            log.debug(
                    "消费失败记录未更新，事件可能已离开 RECEIVED 状态: eventId={}",
                    eventId
            );
        }
    }

    /**
     * 产生唯一键冲突后，去对应列查找
     * @param event
     * @param semanticPayloadHash
     * @return
     */
    private RegistrationResult handleDuplicate(
            VideoStatusChangedEvent event,
            String semanticPayloadHash
    ) {
        VideoStatusConsumedEvent existing =
                consumedEventMapper.selectByEventIdForUpdate(event.eventId());
        if (existing == null) {
            throw new RetryableMessageException(
                    "唯一键冲突后未查询到消费事件，eventId=" + event.eventId()
            );
        }
        // 查看内容是否相同，不同的话说明有bug，一个唯一键对应了两个不同的事件
        boolean sameContent = Objects.equals(existing.getVid(), event.vid())
                && Objects.equals(existing.getEventType(), event.type().name())
                && Objects.equals(existing.getDelta(), event.delta())
                && Objects.equals(
                existing.getPayloadHash(),
                semanticPayloadHash
        );
        if (!sameContent) {
            throw new NonRetryableMessageException(
                    "相同 eventId 对应不同视频统计事件，eventId="
                            + event.eventId()
            );
        }

        Integer status = existing.getProcessStatus();
        if (Objects.equals(status,
                VideoStatusConsumeProcessStatus.RECEIVED.getCode())) {
            return handleReceived(existing);
        }
        if (Objects.equals(status,
                VideoStatusConsumeProcessStatus.REDIS_APPLIED_PENDING_FLUSH
                        .getCode())) {
            return RegistrationResult.REDIS_ALREADY_APPLIED;
        }
        if (Objects.equals(status,
                VideoStatusConsumeProcessStatus.FLUSHED.getCode())) {
            return RegistrationResult.ALREADY_FLUSHED;
        }
        if (Objects.equals(status,
                VideoStatusConsumeProcessStatus.REPAIR_REQUIRED.getCode())) {
            throw new RepairRequiredMessageException(
                    "消费事件已进入人工修复，eventId=" + existing.getEventId(),
                    true
            );
        }
        throw new NonRetryableMessageException(
                "消费事件存在未知处理状态，eventId=" + existing.getEventId()
                        + "，status=" + status
        );
    }

    private RegistrationResult handleReceived(
            VideoStatusConsumedEvent existing
    ) {
        // 现在减去最长允许期限得到的时间，在该时间到现在处理的事件都没有过期
        LocalDateTime replayCutoff = LocalDateTime.now().minusSeconds(
                properties.getConsumerRecoveryAutoReplayMaxAgeSeconds()
        );
        boolean expired = existing.getConsumedAt() == null
                || !existing.getConsumedAt().isAfter(replayCutoff);
        // 超过时限后就不再重新投递，直接记录为失败
        if (expired) {
            String error = truncate(
                    "RECEIVED 事件超过自动重放安全年龄，禁止继续应用 Redis，eventId="
                            + existing.getEventId()
            );
            int updated = consumedEventMapper.markRepairRequired(
                    existing.getEventId(),
                    VideoStatusConsumeProcessStatus.RECEIVED.getCode(),
                    error
            );
            if (updated != 1) {
                throw new RetryableMessageException(
                        "超龄事件转人工修复失败，eventId=" + existing.getEventId()
                );
            }
            throw new RepairRequiredMessageException(error, true);
        }
        // 没有过期，继续尝试更新redis
        if (consumedEventMapper.touchReceivedAttempt(
                existing.getEventId()
        ) != 1) {
            throw new RetryableMessageException(
                    "刷新 RECEIVED 事件尝试时间失败，eventId="
                            + existing.getEventId()
            );
        }
        return RegistrationResult.NEEDS_REDIS_APPLY;
    }

    private String serialize(VideoStatusChangedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new NonRetryableMessageException(
                    "视频统计事件 payload 序列化失败",
                    e
            );
        }
    }

    private void validatePayloadHash(String payloadHash) {
        if (payloadHash == null || payloadHash.length() != 64) {
            throw new NonRetryableMessageException(
                    "视频统计事件语义摘要不合法"
            );
        }
    }

    private String truncate(String message) {
        String value = message == null || message.isBlank()
                ? "unknown error"
                : message;
        return value.length() <= MAX_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_LENGTH);
    }
}
