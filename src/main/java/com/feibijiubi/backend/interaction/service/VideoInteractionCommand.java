package com.feibijiubi.backend.interaction.service;

/**
 * T15：一次交互计数请求（内部命令对象，不是 HTTP DTO）。
 *
 * <p>{@code requestKey} 由调用方生成并在重试时保持不变，是“同一业务请求”的唯一标识；
 * {@code actorHash} 必须是规范化小写 64 位十六进制串（由第二阶段的身份解析产生）。
 * 字段校验在 {@code VideoInteractionService#record} 里做，校验失败返回 400，
 * 不在 record 构造器里抛裸 {@link IllegalArgumentException}。</p>
 */
public record VideoInteractionCommand(
        VideoInteractionActorType actorType,
        String actorHash,
        Integer vid,
        VideoInteractionAction action,
        String requestKey
) {
}
