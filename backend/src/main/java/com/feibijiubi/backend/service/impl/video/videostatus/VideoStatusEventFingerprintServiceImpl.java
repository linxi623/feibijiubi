package com.feibijiubi.backend.service.impl.video.videostatus;

import com.feibijiubi.backend.common.NonRetryableMessageException;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusEventFingerprintService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class VideoStatusEventFingerprintServiceImpl
        implements VideoStatusEventFingerprintService {

    /**
     * 计算消息的语义哈希值（Semantic Hash），主要用于幂等性去重和消息唯一性校验
     * @param event
     * @return
     */
    @Override
    public String hash(VideoStatusChangedEvent event) {
        event.validate();

        StringBuilder semanticPayload = new StringBuilder();
        appendField(semanticPayload, event.eventId());
        appendField(semanticPayload, event.vid());
        appendField(semanticPayload, event.type());
        appendField(semanticPayload, event.delta());
        appendField(semanticPayload, event.hotScoreDelta());
        appendField(semanticPayload, event.occurredAt());
        appendField(semanticPayload, event.schemaVersion());
        appendField(semanticPayload, event.traceId());

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(semanticPayload.toString().getBytes(
                            StandardCharsets.UTF_8
                    ));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new NonRetryableMessageException(
                    "视频统计事件语义摘要计算失败",
                    e
            );
        }
    }

    private void appendField(StringBuilder builder, Object value) {
        String text = String.valueOf(value);
        builder.append(text.length()).append(':').append(text);
    }
}
