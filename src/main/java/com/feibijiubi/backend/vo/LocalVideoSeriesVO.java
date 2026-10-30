package com.feibijiubi.backend.vo;

import java.time.LocalDateTime;

/**
 * 本地作品的安全视图（创建与分页查询共用同一个 VO）。
 *
 * <p>只暴露 {@code id} / {@code localIdentity} / {@code title} / {@code createdBy} / {@code createdAt}。
 * 没有来源、上游地址、统计或视频字段：本片不绑定来源，也不创建视频。</p>
 *
 * @param id            本地主键，BIGINT（{@code Long}）
 * @param localIdentity 服务端生成的随机 UUIDv4（规范小写 8-4-4-4-12），不是标题/序号/地址
 * @param title         trim 后的展示标题；自由文本，渲染需前端转义
 * @param createdBy     创建者（登录管理员 uid）；不是未来 {@code video.owner}
 * @param createdAt     数据库真实时间戳（由 INSERT 后的回读得到，不在应用层捏造）
 */
public record LocalVideoSeriesVO(
        Long id,
        String localIdentity,
        String title,
        Integer createdBy,
        LocalDateTime createdAt
) {
}
