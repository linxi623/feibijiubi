package com.feibijiubi.backend.integration.videosource.applecms;

import java.util.List;

/**
 * E2-B：上游 {@code ?ac=list} 一页的只读标准化快照。
 *
 * <p>只承载三样东西：目录概要条目、分类标识、分页元数据。它<b>不</b>是详情，也<b>不</b>是播放凭据：列表响应
 * 里即便出现 {@code vod_play_from} / {@code vod_play_url}，本模型也不读取——列表可能压根没有播放地址，把列表
 * 当详情用只会拿到半截线路。播放线路只能来自 {@link AppleCmsJsonAdapter#parseDetail(byte[])}。</p>
 *
 * <p>不可变：{@code items} 由 {@link List#copyOf} 复制，元素是不可变 record；调用方拿不到可改动的视图。</p>
 *
 * <p>{@code toString} 显式覆盖：只输出固定类名与条目数，条目里的标题/分类名原文一律不输出
 * （见 {@link AppleCmsCatalogEntry}）。{@code pagination} 只含四个 {@code long}，没有上游文本，可以直接拼接。</p>
 *
 * @param items      本页目录概要；空页是合法的（{@code total=0}/{@code pagecount=0}/{@code page=1}）
 * @param pagination 上游自报的分页元数据，仅作快照记录，见 {@link AppleCmsPagination}
 */
public record AppleCmsListSnapshot(List<AppleCmsCatalogEntry> items, AppleCmsPagination pagination) {

    public AppleCmsListSnapshot {
        items = List.copyOf(items);
        if (pagination == null) {
            throw new NullPointerException("pagination 不能为空");
        }
    }

    /** 只含条目数与分页数字的固定摘要，见类注释。 */
    @Override
    public String toString() {
        return "AppleCmsListSnapshot[itemCount=" + items.size() + ", pagination=" + pagination + "]";
    }
}
