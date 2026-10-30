package com.feibijiubi.backend.videosource.integration.applecms;

/**
 * E2-B：{@code ?ac=list} 中一条作品的<b>目录概要</b>。
 *
 * <p>只保留接入真正需要的四样东西：作品身份、标题快照、上游分类标识与分类名。演员、导演、简介、地区、
 * 语言、上映时间、备注、{@code vod_class} 标签等字段一律不读取——它们要么是展示用的可变文本，要么可能
 * 混入 HTML/脚本，接入层不需要，读进来只会扩大跨站内容风险。</p>
 *
 * <p>{@code workIdentity} 是<b>作品</b>身份，来自上游 {@code vod_id}，规范化成不带前导零的十进制字符串
 * （与 {@code video_source_series.upstream_series_id VARCHAR(191)} 对齐）。它<b>不是</b>剧集身份，
 * 本适配器不生成任何剧集身份，也不按标题/序号合成 ID。</p>
 *
 * <p>{@code title} 是有界快照（最多 255 字符，超限即失败，不截断），仅用于展示与人工核对，<b>不是</b>
 * 任何身份的一部分。{@code categoryId} / {@code categoryName} 缺失时为 {@code null}：上游没给分类就是
 * 未知，不猜、不填默认值。</p>
 *
 * <h2>构造器的校验边界</h2>
 * <p>compact constructor 只做 {@code null}/空串检查。"标题最多 255 字符、{@code categoryId} 是规范十进制、
 * 文本里没有 HTML/脚本"这些性质<b>只</b>由 {@link AppleCmsJsonAdapter} 的解析产物保证；直接 {@code new}
 * 出来的实例<b>不是</b>已验证对象，不允许直接持久化或当可信数据使用。本 record 不重复实现长度/类型校验
 * （那会把解析器常量复制一份，两处规则迟早分叉）。</p>
 *
 * <h2>{@code toString}</h2>
 * <p>显式覆盖：标题与分类名都是上游原文，可能包含 URL、签名或凭据，所以默认 {@code toString} 不适合进日志。
 * 覆盖后只输出固定类名与四个字段的脱敏占位符，不输出任何上游文本（也不截断文本冒充脱敏）。</p>
 *
 * @param workIdentity 作品身份（上游 {@code vod_id} 的规范十进制字符串），非空
 * @param title        标题快照，非空，最多 255 字符（长度上限由解析器保证）
 * @param categoryId   上游 {@code type_id} 的规范十进制字符串，可为 {@code null}（上游未给出）
 * @param categoryName 上游 {@code type_name} 的有界文本，可为 {@code null}
 */
public record AppleCmsCatalogEntry(String workIdentity, String title, String categoryId, String categoryName) {

    public AppleCmsCatalogEntry {
        if (workIdentity == null || workIdentity.isEmpty()) {
            throw new NullPointerException("workIdentity 不能为空");
        }
        if (title == null || title.isEmpty()) {
            throw new NullPointerException("title 不能为空");
        }
    }

    /** 不含任何上游文本的固定摘要，见类注释。 */
    @Override
    public String toString() {
        return "AppleCmsCatalogEntry[workIdentity=<已脱敏>, title=<已脱敏>"
                + ", categoryId=<已脱敏>, categoryName=<已脱敏>]";
    }
}
