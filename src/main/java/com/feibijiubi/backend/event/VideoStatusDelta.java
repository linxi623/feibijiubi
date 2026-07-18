package com.feibijiubi.backend.event;

import com.feibijiubi.backend.event.VideoStatusChangedEvent;

public record VideoStatusDelta(
        long playDelta,
        long likeDelta,
        long unlikeDelta,
        long commentDelta,
        long coinDelta,
        long shareDelta,
        long collectDelta,
        long danmuDelta
) {
    public static VideoStatusDelta from(VideoStatusChangedEvent event) {
        long delta = event.delta();
        return switch (event.type()) {
            case PLAY -> new VideoStatusDelta(delta, 0, 0, 0, 0, 0, 0, 0);
            case LIKE -> new VideoStatusDelta(0, delta, 0, 0, 0, 0, 0, 0);
            case UNLIKE -> new VideoStatusDelta(0, 0, delta, 0, 0, 0, 0, 0);
            case COMMENT -> new VideoStatusDelta(0, 0, 0, delta, 0, 0, 0, 0);
            case COIN -> new VideoStatusDelta(0, 0, 0, 0, delta, 0, 0, 0);
            case SHARE -> new VideoStatusDelta(0, 0, 0, 0, 0, delta, 0, 0);
            case COLLECT -> new VideoStatusDelta(0, 0, 0, 0, 0, 0, delta, 0);
            case DANMU -> new VideoStatusDelta(0, 0, 0, 0, 0, 0, 0, delta);
        };
    }
}