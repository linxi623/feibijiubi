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
        // T02: 批次累计溢出必须显式失败，不能静默回绕。
        return new VideoStatusDelta(
                Math.addExact(playDelta, other.playDelta),
                Math.addExact(likeDelta, other.likeDelta),
                Math.addExact(unlikeDelta, other.unlikeDelta),
                Math.addExact(commentDelta, other.commentDelta),
                Math.addExact(coinDelta, other.coinDelta),
                Math.addExact(shareDelta, other.shareDelta),
                Math.addExact(collectDelta, other.collectDelta),
                Math.addExact(danmuDelta, other.danmuDelta)
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
