package com.feibijiubi.backend.service.video.videostatus;

import com.feibijiubi.backend.event.VideoStatusDelta;

import java.util.List;
import java.util.Objects;

/**
 * Redis 重建所需的纯 Java 快照。该对象离开 MySQL 事务后即可安全使用。
 */
public record VideoStatusRebuildSnapshot(
        Integer vid,
        long playTimes,
        long likeTimes,
        long unlikeTimes,
        long commentTimes,
        long coinTimes,
        long shareTimes,
        long collectTimes,
        long danmuTimes,
        List<Candidate> candidates
) {
    public VideoStatusRebuildSnapshot {
        Objects.requireNonNull(vid, "vid 不能为空");
        candidates = List.copyOf(candidates);
    }

    public record Candidate(
            String eventId,
            int processStatus,
            VideoStatusDelta delta,
            double hotScoreDelta
    ) {
        public Candidate {
            Objects.requireNonNull(eventId, "eventId 不能为空");
            Objects.requireNonNull(delta, "delta 不能为空");
        }
    }
}
