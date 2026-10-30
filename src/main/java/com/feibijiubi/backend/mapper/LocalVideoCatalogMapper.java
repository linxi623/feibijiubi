package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.LocalVideoEpisode;
import com.feibijiubi.backend.entity.LocalVideoSeries;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 本地作品/剧集（{@code local_video_series} / {@code local_video_episode}）的最小 mapper。
 *
 * <p>只有五条读 + 两条写，全部是“新建”与“按 keyset/主键读”：</p>
 * <ul>
 *   <li>两条 INSERT 都<b>在列级省略</b>数据库自己负责的列（{@code created_at} / {@code updated_at}）；
 *       剧集的 {@code duration_seconds} / {@code vid} 也刻意不出现在列清单里，由数据库 NULL 默认值决定，
 *       因此本片没有“服务端给时长/vid 赋值”的入口；</li>
 *   <li>没有任何 {@code UPDATE} / {@code DELETE} / 合并且不按标题查询：本片没有修改、删除、导入或
 *       同标题自动合并；</li>
 *   <li>分页一律 {@code id > #{afterId} ORDER BY id LIMIT #{limit}}，剧集再加
 *       {@code series_id = #{seriesId}}，不用 {@code OFFSET}；</li>
 *   <li>不联表 {@code video} / {@code video_status} / 任何来源表，也不碰统计账本。</li>
 * </ul>
 *
 * <p>唯一键与外键仍然是最终防线：{@code local_identity}、{@code vid} 唯一，{@code series_id} 外键引用
 * {@code local_video_series(id)}。本片依赖外键兜底，但没有为此设计锁或重试 —— 也没有删除接口，
 * 所以不存在“父行被并发删掉”的窗口。</p>
 */
@Mapper
public interface LocalVideoCatalogMapper {

    /** 插入本地作品；生成主键回填到 {@code series.id}（{@code useGeneratedKeys}）。 */
    int insertSeries(LocalVideoSeries series);

    /** 按主键读本地作品（创建后回读真实时间戳、以及创建剧集前的父存在性检查）。 */
    LocalVideoSeries selectSeriesById(@Param("id") long id);

    /** keyset 分页：{@code id > afterId}，按 {@code id} 升序，最多 {@code limit} 行。 */
    List<LocalVideoSeries> selectSeriesAfter(@Param("afterId") long afterId,
                                             @Param("limit") int limit);

    /** 插入本地剧集；生成主键回填到 {@code episode.id}。 */
    int insertEpisode(LocalVideoEpisode episode);

    /** 按主键读本地剧集（创建后回读数据库真实时间戳与 NULL 的时长/vid）。 */
    LocalVideoEpisode selectEpisodeById(@Param("id") long id);

    /**
     * keyset 分页：先按 {@code series_id} 等值，再 {@code id > afterId}，按 {@code id} 升序，
     * 最多 {@code limit} 行（命中 {@code (series_id, id)} 索引）。
     */
    List<LocalVideoEpisode> selectEpisodesAfter(@Param("seriesId") long seriesId,
                                                @Param("afterId") long afterId,
                                                @Param("limit") int limit);
}
