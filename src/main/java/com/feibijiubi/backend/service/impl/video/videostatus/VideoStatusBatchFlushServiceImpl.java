package com.feibijiubi.backend.service.impl.video.videostatus;

import com.feibijiubi.backend.common.RetryableMessageException;
import com.feibijiubi.backend.common.VideoStatusFlushDataException;
import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.event.VideoStatusDelta;
import com.feibijiubi.backend.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusBatchFlushService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VideoStatusBatchFlushServiceImpl
        implements VideoStatusBatchFlushService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final VideoStatusConsumedEventMapper consumedEventMapper;
    private final VideoStatusMapper videoStatusMapper;

    /**
     * 取一个视频的一批数据进行刷库，如果没有刷库，就直接返回。
     * @param vid
     * @param limit
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void flushOneVideo(Integer vid, int limit) {
        if (vid == null || vid <= 0 || limit <= 0) {
            throw new IllegalArgumentException("刷库 vid 和 limit 不合法");
        }

        List<VideoStatusConsumedEvent> events =
                consumedEventMapper.selectPendingForUpdate(vid, limit);
        if (events.isEmpty()) {
            return;
        }

        List<Long> ids = events.stream()
                .map(VideoStatusConsumedEvent::getId)
                .toList();
        VideoStatusDelta delta;
        try {
            delta = events.stream()
                    .map(VideoStatusDelta::from)
                    .reduce(VideoStatusDelta.zero(), VideoStatusDelta::plus);
        } catch (IllegalArgumentException e) {
            throw new VideoStatusFlushDataException(
                    vid,
                    ids,
                    "消费事件包含未知统计类型"
            );
        }

        if (!delta.isZero()
                && videoStatusMapper.applyBatchDelta(vid, delta) != 1) {
            throw new VideoStatusFlushDataException(
                    vid,
                    ids,
                    "视频统计批量更新违反非负约束"
            );
        }

        if (consumedEventMapper.markFlushed(ids) != ids.size()) {
            throw new RetryableMessageException(
                    "事件 FLUSHED 数量与锁定数量不一致，vid=" + vid
            );
        }

    }

    @Override
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class
    )
    public void markRepairRequired(
            List<Long> consumedEventIds,
            String lastError
    ) {
        if (consumedEventIds == null || consumedEventIds.isEmpty()) {
            return;
        }
        int updated = consumedEventMapper.markFlushRepairRequired(
                consumedEventIds,
                truncate(lastError)
        );
        if (updated != consumedEventIds.size()) {
            throw new RetryableMessageException(
                    "刷库异常事件转人工修复数量不一致"
            );
        }
    }

    private String truncate(String message) {
        String value = message == null || message.isBlank()
                ? "unknown flush data error"
                : message;
        return value.length() <= MAX_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_LENGTH);
    }
}
