package com.feibijiubi.backend.videostats.service.impl;

import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.entity.VideoStatusConsumedEvent;
import com.feibijiubi.backend.videostats.mapper.VideoStatusConsumedEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * T09 短事务领取：每次只领取一条候选，提交后立即处理，再领取下一条。
 * 这样不会出现“先预领一整批、后面的条目在处理前租约已过期”的问题。
 * 这里绝不等待 RabbitMQ confirm，也不开长事务。
 */
@Service
@RequiredArgsConstructor
public class VideoStatusStatus0CompensationClaimService {

    private final VideoStatusConsumedEventMapper consumedEventMapper;
    private final VideoStatusProperties properties;

    /**
     * @param claimed       成功 CAS 的条目（0 或 1 条）
     * @param lastScannedId 本次实际扫描到的候选 id；CAS 冲突也照常前进，避免尾部饿死
     */
    public record ClaimBatch(
            List<VideoStatusConsumedEvent> claimed,
            Long lastScannedId
    ) {
    }

    @Transactional(rollbackFor = Exception.class)
    public ClaimBatch claimNext(Long afterId) {
        List<VideoStatusConsumedEvent> candidates =
                consumedEventMapper.selectStatus0CompensationCandidates(
                        properties.getStatus0CompensationMinAgeSeconds(),
                        properties.getConsumerRecoveryAutoReplayMaxAgeSeconds(),
                        properties.getStatus0CompensationMaxAttempts(),
                        afterId,
                        1
                );
        if (candidates.isEmpty()) {
            return new ClaimBatch(List.of(), null);
        }

        VideoStatusConsumedEvent candidate = candidates.get(0);
        String leaseToken = UUID.randomUUID().toString();
        int rows = consumedEventMapper.claimStatus0Compensation(
                candidate.getEventId(),
                leaseToken,
                properties.getStatus0CompensationLeaseSeconds(),
                properties.getStatus0CompensationMinAgeSeconds(),
                properties.getConsumerRecoveryAutoReplayMaxAgeSeconds(),
                properties.getStatus0CompensationMaxAttempts()
        );
        if (rows == 1) {
            candidate.setCompensationLeaseToken(leaseToken);
            return new ClaimBatch(List.of(candidate), candidate.getId());
        }
        return new ClaimBatch(List.of(), candidate.getId());
    }
}
