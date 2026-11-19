package com.feibijiubi.backend.videosource.mapper;

import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.videosource.entity.LocalEpisodePlaybackLine;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * M6-A 只读播放/选集的持久化语句。
 *
 * <p>只有 SELECT：本接口不写 video、video_url、video_status、user_video、统计事件或 Redis。
 * 线路查询按本地剧集 {@code episode_id} 限定，最多 33 行（32 条业务上限 + 1 行溢出探测）；
 * 选集查询按 {@code series_id} 限定并用 {@code id > afterId} keyset 分页，没有 OFFSET 或 COUNT。</p>
 *
 * <p>{@link #selectPlaybackLines(long)} 会读取候选 {@code address}（服务端敏感值），调用方只能把
 * 选中线路的地址放进播放响应，不得记录、返回摘要或序列化整行。</p>
 */
@Mapper
public interface VideoPlaybackMapper {

    /** 按 {@code video.vid} 找唯一绑定的本地剧集；未导入/旧 COS 视频返回 null。 */
    LocalVideoEpisode selectEpisodeByVid(@Param("vid") int vid);

    /** 当前剧集的线路指针（binding → candidate），按本地 binding id 升序，最多 33 行。 */
    List<LocalEpisodePlaybackLine> selectPlaybackLines(@Param("episodeId") long episodeId);

    /**
     * 同一本地作品下对当前调用者可见的剧集：已发布、未软删、公开或本人，按本地 episode.id keyset
     * 升序，最多 {@code limit} 行。{@code uid} 为 null（未登录）时只命中公开剧集。
     */
    List<LocalVideoEpisode> selectVisibleEpisodesAfter(@Param("seriesId") long seriesId,
                                                       @Param("afterId") long afterId,
                                                       @Param("uid") Integer uid,
                                                       @Param("limit") int limit);
}
