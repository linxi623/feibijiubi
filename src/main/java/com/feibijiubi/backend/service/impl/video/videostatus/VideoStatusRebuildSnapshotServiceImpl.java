package com.feibijiubi.backend.service.impl.video.videostatus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusDelta;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildSnapshot;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class VideoStatusRebuildSnapshotServiceImpl implements VideoStatusRebuildSnapshotService {

    private final VideoStatusMapper videoStatusMapper;
    private final VideoStatusConsumedEventMapper consumedEventMapper;
    private final ObjectMapper objectMapper;


    @Override
    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public VideoStatusRebuildSnapshot load(Integer vid) {
        validateVid(vid);

        VideoStatus baseline = videoStatusMapper.selectByVid(vid);
        if (baseline == null) {
            throw new RetryableMessageException(
                    "视频统计 MySQL 基线不存在，vid=" + vid
            );
        }

        List<VideoStatusRebuildSnapshot.Candidate> candidates =
                consumedEventMapper.selectRebuildCandidates(vid)
                        .stream()
                        .map(this::toCandidate)
                        .toList();

        return new VideoStatusRebuildSnapshot(
                vid,
                valueOrZero(baseline.getPlayTimes()),
                valueOrZero(baseline.getLikeTimes()),
                valueOrZero(baseline.getUnlikeTimes()),
                valueOrZero(baseline.getCommentTimes()),
                valueOrZero(baseline.getCoinTimes()),
                valueOrZero(baseline.getShareTimes()),
                valueOrZero(baseline.getCollectTimes()),
                valueOrZero(baseline.getDanmuTimes()),
                candidates
        );
    }

    private VideoStatusRebuildSnapshot.Candidate toCandidate(
            VideoStatusConsumedEvent row
    ) {
        if (row.getProcessStatus() == null
                || (row.getProcessStatus() != 0
                && row.getProcessStatus() != 1)) {
            throw new RetryableMessageException(
                    "Redis 重建候选事件状态非法，eventId=" + row.getEventId()
            );
        }
        if (row.getPayload() == null || row.getPayload().isBlank()) {
            throw new RetryableMessageException(
                    "Redis 重建候选事件缺少规范化 payload，eventId=" + row.getEventId()
            );
        }

        VideoStatusChangedEvent event;
        try {
            event = objectMapper.readValue(
                    row.getPayload(),
                    VideoStatusChangedEvent.class
            );
            event.validate();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new RetryableMessageException(
                    "Redis 重建候选事件 payload 非法，eventId=" + row.getEventId(),
                    e
            );
        }

        if (!Objects.equals(row.getEventId(), event.eventId())
                || !Objects.equals(row.getVid(), event.vid())
                || !Objects.equals(row.getEventType(), event.type().name())
                || !Objects.equals(row.getDelta(), event.delta())) {
            throw new RetryableMessageException(
                    "Redis 重建候选事件与 payload 不一致，eventId=" + row.getEventId()
            );
        }

        return new VideoStatusRebuildSnapshot.Candidate(
                event.eventId(),
                row.getProcessStatus(),
                VideoStatusDelta.from(event),
                event.hotScoreDelta()
        );
    }

    private void validateVid(Integer vid) {
        if (vid == null || vid <= 0) {
            throw new IllegalArgumentException("vid 必须大于 0");
        }
    }

    private long valueOrZero(Integer value) {
        return value == null ? 0L : value.longValue();
    }
}
