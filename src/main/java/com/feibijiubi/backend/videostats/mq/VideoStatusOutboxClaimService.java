package com.feibijiubi.backend.videostats.mq;

import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.entity.VideoStatusOutbox;
import com.feibijiubi.backend.videostats.mapper.VideoStatusOutboxMapper;
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

    /**
     * 从消息表中领取任务（批量大小取配置 {@code outbox-batch-size}）。
     *
     * <p>保留无参重载是为了不改变既有调用方/测试的契约；T06 的调度器改用
     * {@link #claimBatch(int)} 逐条领取。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public List<VideoStatusOutbox> claimBatch() {
        return claim(properties.getOutboxBatchSize());
    }

    /**
     * 领取最多 {@code limit} 条待发送任务。
     *
     * <p>加上线程的唯一标识，同时将消息状态改为 SENDING，标记消息发送的时间。
     * 每次调用是一个独立事务：领取即提交，后续发布失败不影响其它已领取事件的状态。
     * 调度器每轮逐条调用（{@code claimBatch(1)}），避免“预领一批、慢慢 publish”
     * 让后面的消息在排队期间就把租约耗完。</p>
     *
     * @param limit 本次最多领取多少条，必须落在 1..{@link VideoStatusProperties#MAX_BATCH_SIZE}
     */
    @Transactional(rollbackFor = Exception.class)
    public List<VideoStatusOutbox> claimBatch(int limit) {
        if (limit <= 0 || limit > VideoStatusProperties.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "claimBatch limit 必须在 1 到 "
                            + VideoStatusProperties.MAX_BATCH_SIZE
                            + " 之间，当前值：" + limit
            );
        }
        return claim(limit);
    }

    private List<VideoStatusOutbox> claim(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<VideoStatusOutbox> pending =
                outboxMapper.selectPendingForUpdate(
                        limit,
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
