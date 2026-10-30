package com.feibijiubi.backend.vo;

/**
 * M3-B：管理员预览接口里的<b>来源摘要</b>。
 *
 * <p>只有两样东西：部署白名单引用键 {@code code} 与展示名 {@code name}。刻意<b>不</b>包含
 * API base、允许端点、媒体支持情况、启用开关或任何探测结果 —— 这些是服务端配置细节，
 * 泄露它等于把"我们信任哪些上游"抄给调用方。</p>
 *
 * <p>{@code code} 不是数据库主键，是部署配置里的字符串键（大小写敏感）。</p>
 *
 * @param code 部署白名单引用键
 * @param name 展示名（部署配置里的运维文本）
 */
public record ExternalVideoSourceVO(String code, String name) {
}
