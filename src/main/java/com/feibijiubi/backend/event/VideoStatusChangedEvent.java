package com.feibijiubi.backend.event;

import java.time.LocalDateTime;

public record VideoStatusChangedEvent(
        String eventId,
        Integer vid,
        VideoStatusEventType type,
        Long delta,
        Double hotScoreDelta,
        LocalDateTime occurredAt,
        Integer schemaVersion,
        String traceId
) {
    public void validate() {
        if (eventId == null || eventId.isBlank() || eventId.length() > 64) {
            throw new IllegalArgumentException("eventId 不合法");
        }
        if (vid == null || vid <= 0) {
            throw new IllegalArgumentException("vid 不合法");
        }
        if (type == null || delta == null || delta == 0) {
            throw new IllegalArgumentException("统计类型或增量不合法");
        }
        // T02: 计数支持 0..Long.MAX_VALUE；绝对值超过 Long.MAX 的增量永远不可能合法。
        if (delta == Long.MIN_VALUE) {
            throw new IllegalArgumentException("统计增量超出 Long 范围");
        }
        double expected = type.calculateHotScore(delta);
        if (hotScoreDelta == null
                || !Double.isFinite(hotScoreDelta)
                || Double.compare(hotScoreDelta, expected) != 0) {
            throw new IllegalArgumentException("热门分数增量不合法");
        }
        if (occurredAt == null || !Integer.valueOf(2).equals(schemaVersion)) {
            throw new IllegalArgumentException("消息版本或时间不合法");
        }
    }
}
