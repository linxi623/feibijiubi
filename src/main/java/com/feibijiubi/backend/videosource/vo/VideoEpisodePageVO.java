package com.feibijiubi.backend.videosource.vo;

import java.util.List;

/**
 * M6-A 有界选集页：只有可见剧集的 {@code vid} 与本地展示标题，没有总数、不可见集数量、线路或地址。
 *
 * <p>游标基于本地 {@code local_video_episode.id}，{@code hasMore=true} 时给出下一页游标；
 * 末页 {@code nextCursor=null}，不伪造游标。</p>
 */
public record VideoEpisodePageVO(List<Item> items, boolean hasMore, String nextCursor) {

    public VideoEpisodePageVO {
        items = List.copyOf(items);
    }

    /** 单条可见剧集；{@code displayTitle} 可为 null（本地未设置展示标题）。 */
    public record Item(Integer vid, String displayTitle) {
    }
}
