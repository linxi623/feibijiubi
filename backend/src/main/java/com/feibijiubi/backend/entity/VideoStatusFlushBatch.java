package com.feibijiubi.backend.entity;

import com.feibijiubi.backend.enums.VideoStatusFlushCleanupStatus;
import com.feibijiubi.backend.event.VideoStatusDelta;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VideoStatusFlushBatch {
    private Long id;
    private String batchId;
    private Integer vid;
    private String redisGeneration;
    private Long playDelta;
    private Long likeDelta;
    private Long unlikeDelta;
    private Long commentDelta;
    private Long coinDelta;
    private Long shareDelta;
    private Long collectDelta;
    private Long danmuDelta;
    private Integer cleanupStatus;
    private Integer cleanupRetryCount;
    private LocalDateTime lastAttemptAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime cleanedAt;

    public static VideoStatusFlushBatch create(
            String batchId,
            Integer vid,
            String redisGeneration,
            VideoStatusDelta delta
    ) {
        VideoStatusFlushBatch batch = new VideoStatusFlushBatch();
        batch.setBatchId(batchId);
        batch.setVid(vid);
        batch.setRedisGeneration(redisGeneration);
        batch.setPlayDelta(delta.playDelta());
        batch.setLikeDelta(delta.likeDelta());
        batch.setUnlikeDelta(delta.unlikeDelta());
        batch.setCommentDelta(delta.commentDelta());
        batch.setCoinDelta(delta.coinDelta());
        batch.setShareDelta(delta.shareDelta());
        batch.setCollectDelta(delta.collectDelta());
        batch.setDanmuDelta(delta.danmuDelta());
        batch.setCleanupStatus(
                VideoStatusFlushCleanupStatus.PENDING.getCode()
        );
        batch.setCleanupRetryCount(0);
        return batch;
    }
}
