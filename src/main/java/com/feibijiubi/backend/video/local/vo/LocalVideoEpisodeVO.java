package com.feibijiubi.backend.video.local.vo;

import java.time.LocalDateTime;

/**
 * 本地剧集的安全视图（创建与分页查询共用同一个 VO）。
 *
 * <p>新草稿的vid与durationSeconds均为null；S2确认后查询会返回真实统一时长。
 * 不编造0或ready/imported结论，vid仍待后续导入切片绑定。</p>
 *
 * @param id              本地主键，BIGINT（{@code Long}）
 * @param localIdentity   服务端生成的随机 UUIDv4
 * @param seriesId        所属本地作品 id
 * @param displayTitle    trim 后的展示标题，可为 {@code null}；自由文本，渲染需前端转义
 * @param vid             未来导入后绑定的 {@code video.vid}；本片恒为 {@code null}
 * @param durationSeconds 管理员确认的统一时长，未确认为null
 * @param createdBy       创建者（登录管理员 uid）
 * @param createdAt       数据库真实时间戳（回读所得）
 */
public record LocalVideoEpisodeVO(
        Long id,
        String localIdentity,
        Long seriesId,
        String displayTitle,
        Integer vid,
        Double durationSeconds,
        Integer createdBy,
        LocalDateTime createdAt
) {
}
