package com.feibijiubi.backend.interaction.service;

/**
 * T15：交互计数的 actor 类型。
 *
 * <p>数据库只保存 {@code actor_type} + 规范化 64 位十六进制 {@code actor_hash}，不保存
 * uid、IP、User-Agent 或任何可回推个人的原文。身份解析（登录态 → USER + uid 哈希；
 * 匿名 → 客户端随机 UUID 的哈希）属于 T15 第二阶段接线，不在本阶段的 DB 核心里做。</p>
 *
 * <p><b>匿名必须是随机 UUID，不能是设备/浏览器指纹</b>：指纹会跨站跨会话稳定识别自然人，
 * 与“最小化存储”冲突。DB 层无法验证哈希来源，这一点只能由第二阶段的调用方保证。</p>
 */
public enum VideoInteractionActorType {
    /** 已登录用户。 */
    USER,
    /** 匿名访客（随机 UUID，非指纹）。 */
    ANONYMOUS
}
