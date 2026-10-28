package com.feibijiubi.backend.service.video.interaction;

/**
 * T15 第二阶段：一次交互请求的 actor 身份（只含类型 + 规范化哈希）。
 *
 * <p>这里刻意<b>不</b>携带 uid、原始访客标识、IP 或 User-Agent：只有哈希允许继续往下传，
 * 下游（DB 表、Redis 限流键、日志）拿不到可回推个人的原文。</p>
 *
 * @param type {@code USER} 或 {@code ANONYMOUS}
 * @param hash 规范化小写 64 位十六进制 SHA-256（含域分离前缀，见
 *             {@code VideoInteractionActorResolver}）
 */
public record VideoInteractionActor(
        VideoInteractionActorType type,
        String hash
) {
}
