package com.feibijiubi.backend.service.video.interaction;

import com.feibijiubi.backend.event.VideoStatusEventType;

/**
 * T15 第一阶段允许被限计的交互动作。
 *
 * <p>只开放 {@code PLAY} 与 {@code SHARE}：点赞/投币/收藏已有行锁 + 唯一键 + 余额守卫，
 * 不能用“只有 DB 窗口去重”替换它们，因此不在本阶段范围内。</p>
 */
public enum VideoInteractionAction {
    PLAY(VideoStatusEventType.PLAY),
    SHARE(VideoStatusEventType.SHARE);

    private final VideoStatusEventType eventType;

    VideoInteractionAction(VideoStatusEventType eventType) {
        this.eventType = eventType;
    }

    /** 计数成功后要产生的统计事件类型（异步路径用它建 Outbox 事件，增量固定 +1）。 */
    public VideoStatusEventType eventType() {
        return eventType;
    }
}
