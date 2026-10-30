package com.feibijiubi.backend.video.local.vo;

import java.util.List;

/**
 * 本地作品/剧集的分页页（keyset 风格，与既有管理列表一致：不用 {@code OFFSET}）。
 *
 * <p>取数方式是“请求 {@code limit + 1} 行”：多出来的那一行只用于判断 {@code hasMore}，
 * 不会出现在 {@code items} 里。因此 {@code hasMore} 是实际探测结果，不是猜测。</p>
 *
 * @param items       本页条目，按 {@code id} 升序；空页就是空数组（不代表任何写入）
 * @param afterId     本次请求的回显下界（不含）
 * @param limit       本次请求的回显页大小
 * @param hasMore     是否还有下一页
 * @param nextAfterId {@code hasMore=true} 时是本页最后一条的 id（下一次的 {@code afterId}）；
 *                    没有下一页时为 {@code null}，不伪造一个“末页游标”
 * @param <T>         条目类型（{@link LocalVideoSeriesVO} 或 {@link LocalVideoEpisodeVO}）
 */
public record LocalVideoPageVO<T>(
        List<T> items,
        long afterId,
        int limit,
        boolean hasMore,
        Long nextAfterId
) {

    public LocalVideoPageVO {
        items = List.copyOf(items);
    }
}
