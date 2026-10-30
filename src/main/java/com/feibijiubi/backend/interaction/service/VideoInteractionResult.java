package com.feibijiubi.backend.interaction.service;

/**
 * T15：一次交互计数请求的结果。
 *
 * @param requestKey 请求幂等键（原样回传）
 * @param requestId  {@code video_interaction_request.id}
 * @param state      {@code COUNTED} 或 {@code SUPPRESSED}（来自请求行的终态快照）
 * @param counted    {@code true} 表示这一次（或这次重放对应的原始请求）真的计了一次数
 * @param duplicate  {@code true} 表示命中已存在的 {@code request_key}，是幂等重放而不是新判定
 * @param eventId    仅异步计数路径有值（{@code createEvent} 返回的统计事件 eventId）；
 *                    同步路径为 {@code null}，因为它直接更新 {@code video_status}，不产生 MQ 事件
 */
public record VideoInteractionResult(
        String requestKey,
        Long requestId,
        String state,
        boolean counted,
        boolean duplicate,
        String eventId
) {
}
