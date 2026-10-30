package com.feibijiubi.backend.interaction.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * T15：同一 actor + vid + action 的滚动限计窗口。
 *
 * <p>{@code lastCountedAt} 为 {@code null} 表示<b>从未计数</b>，这是“第一条请求必须计数”
 * 的唯一正确初始状态；绝不能用 {@code NOW(3)} 初始化，否则第一条请求会被误判为
 * “刚计过”而抑制。抑制不推进该字段，所以窗口按实际计数时间滚动。</p>
 */
@Data
public class VideoInteractionWindow {
    private Long id;
    private String actorType;
    private String actorHash;
    private Integer vid;
    private String action;
    private LocalDateTime lastCountedAt;
    private LocalDateTime createdAt;
}
