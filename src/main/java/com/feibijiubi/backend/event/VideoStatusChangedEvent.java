package com.feibijiubi.backend.event;

import com.feibijiubi.backend.common.BusinessException;

import java.time.LocalDateTime;

public record VideoStatusChangedEvent(
        // 用于幂等操作
        String eventId,
        Integer vid,
        // 防止乱序
        Long aggregateSequence,
        VideoStatusEventType type,
        Long delta,
        Double hotScoreDelta,
        LocalDateTime occurredAt,
        // 版本控制用的，方便后续添加新字段
        Integer schemaVersion,
        // 追踪用的Id，当消息进入死信队列可以定位到对应的用户和请求
        String traceId
) {
    public void validate() {
        if (eventId == null || eventId.isBlank() || eventId.length() > 64) {
            throw new BusinessException(500, "eventId 不合法");
        }
        if (vid == null || vid <= 0) {
            throw new BusinessException(500, "vid 不合法");
        }
        if (aggregateSequence == null || aggregateSequence <= 0) {
            throw new BusinessException(500, "aggregateSequence 不合法");
        }
        if (type == null || delta == null || delta == 0) {
            throw new BusinessException(500, "统计类型或增量不合法");
        }
        if (hotScoreDelta == null || !Double.isFinite(hotScoreDelta)) {
            throw new BusinessException(500, "热门分数增量不合法");
        }
        if (occurredAt == null || schemaVersion == null || schemaVersion != 1) {
            throw new BusinessException(500, "消息版本或发生时间不合法");
        }
    }
}
