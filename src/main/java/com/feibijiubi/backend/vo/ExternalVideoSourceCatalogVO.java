package com.feibijiubi.backend.vo;

import java.util.List;

/**
 * M3-B：上游目录一页的<b>脱敏</b>响应体。
 *
 * <h2>返回什么</h2>
 * <ul>
 *   <li>{@code sourceCode}：这次请求的部署白名单引用键（回显请求参数，不含 base）；</li>
 *   <li>{@code page} / {@code pageCount} / {@code total}：上游<b>自报</b>的分页数字，原样展示。
 *       它们不是本地事实：上游可能串页、按权限截断或写错，因此<b>不能</b>用来驱动自动翻页或判断
 *       "还有没有下一页"，翻页必须由管理员手动逐次发起；</li>
 *   <li>{@code items}：本页条目，只有作品身份、标题快照、上游分类标识与分类名。</li>
 * </ul>
 *
 * <h2>不返回什么</h2>
 * <p>播放线路、播放地址、封面外链、演员/导演/简介/备注等自由文本字段一律不返回，连"上游给了但没有"都不
 * 透露。列表响应里的播放字段本来就不属于目录语义（可能是半截数据），这里直接不读。</p>
 *
 * <h2>前端必须做的处理</h2>
 * <p>{@code title} / {@code categoryName} 是<b>上游不可信文本</b>，渲染时必须转义。这里只保证"不返回结构性
 * 敏感字段（地址、线路键、token）"，<b>不能</b>承诺所有自由文本里都不含 URL —— 上游完全可以把链接写进
 * 标题，是否展示由前端决定。</p>
 */
public record ExternalVideoSourceCatalogVO(
        String sourceCode,
        int page,
        long pageCount,
        long total,
        List<Item> items
) {

    public ExternalVideoSourceCatalogVO {
        items = List.copyOf(items);
    }

    /**
     * 目录里的一条作品概要。
     *
     * @param workIdentity 上游作品身份（规范十进制字符串），<b>不是</b>本地 {@code vid}，也不是剧集身份
     * @param title        标题快照（上游不可信文本，渲染需转义）
     * @param categoryId   上游分类标识，上游未给出时为 {@code null}
     * @param categoryName 上游分类名（上游不可信文本），未给出时为 {@code null}
     */
    public record Item(
            String workIdentity,
            String title,
            String categoryId,
            String categoryName
    ) {
    }
}
