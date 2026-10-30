package com.feibijiubi.backend.video.local.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建本地剧集的请求体。
 *
 * <p>只有 {@code displayTitle} 一个字段，且<b>可空</b>（{@code null} = 暂不设展示标题）。
 * {@code seriesId} 只来自路径，{@code localIdentity} / {@code createdBy} / {@code vid} /
 * {@code durationSeconds} 同样不由请求提供。</p>
 *
 * <p>非 {@code null} 时，trim 后不能是空串，长度不能超过 255；{@link Size} 只覆盖长度，
 * “trim 后非空”由服务层判定，因此直接调用服务也受同一套约束。未知字段的处理与
 * {@link LocalVideoSeriesCreateDTO} 相同：忽略，且不生效。</p>
 */
@Data
public class LocalVideoEpisodeCreateDTO {

    @Size(max = 255, message = "displayTitle 长度不能超过 255")
    private String displayTitle;
}
