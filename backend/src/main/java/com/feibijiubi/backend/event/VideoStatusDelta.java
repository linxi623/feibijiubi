package com.feibijiubi.backend.event;

import com.feibijiubi.backend.entity.VideoStatusConsumedEvent;

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
    public static VideoStatusDelta zero() {
        return new VideoStatusDelta(0, 0, 0, 0, 0, 0, 0, 0);
    }

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

    public static VideoStatusDelta from(VideoStatusConsumedEvent event) {
        long delta = event.getDelta();
        return switch (VideoStatusEventType.valueOf(event.getEventType())) {
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

    public VideoStatusDelta plus(VideoStatusDelta other) {
        return new VideoStatusDelta(
                playDelta + other.playDelta,
                likeDelta + other.likeDelta,
                unlikeDelta + other.unlikeDelta,
                commentDelta + other.commentDelta,
                coinDelta + other.coinDelta,
                shareDelta + other.shareDelta,
                collectDelta + other.collectDelta,
                danmuDelta + other.danmuDelta
        );
    }

    public boolean isZero() {
        return playDelta == 0
                && likeDelta == 0
                && unlikeDelta == 0
                && commentDelta == 0
                && coinDelta == 0
                && shareDelta == 0
                && collectDelta == 0
                && danmuDelta == 0;
    }
}
