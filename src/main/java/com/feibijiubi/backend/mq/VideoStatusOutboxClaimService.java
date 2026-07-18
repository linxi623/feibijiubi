package com.feibijiubi.backend.mq;

import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusOutbox;
import com.feibijiubi.backend.mapper.VideoStatusOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoStatusOutboxClaimService {

    private final VideoStatusOutboxMapper outboxMapper;
    private final VideoStatusProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public List<VideoStatusOutbox> claimBatch() {
        LocalDateTime now = LocalDateTime.now();
        List<VideoStatusOutbox> pending =
                outboxMapper.selectPendingForUpdate(
                        properties.getOutboxBatchSize(),
                        now
                );

        List<VideoStatusOutbox> claimed = new ArrayList<>();
        for (VideoStatusOutbox outbox : pending) {
            String leaseToken = UUID.randomUUID().toString();
            int rows = outboxMapper.markSending(
                    outbox.getId(),
                    leaseToken,
                    now
            );
            if (rows == 1) {
                outbox.setLeaseToken(leaseToken);
                outbox.setSendingAt(now);
                claimed.add(outbox);
            }
        }
        return claimed;
    }
}
