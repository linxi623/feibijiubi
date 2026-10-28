package com.feibijiubi.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * T15：一个业务交互请求（{@code request_key}）的幂等记录。
 *
 * <p>插入时只有 {@code state = 0 PENDING} 的占位行；计数与终态在同一个事务里写，
 * 所以已提交的行只可能是 {@code 1 COUNTED} 或 {@code 2 SUPPRESSED}。
 * {@code eventId} 只在异步计数路径（Outbox 事件）上有值，同步路径为 {@code null}。</p>
 */
@Data
public class VideoInteractionRequest {
    private Long id;
    private String requestKey;
    private String actorType;
    private String actorHash;
    private Integer vid;
    private String action;
    private Integer state;
    private String eventId;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
