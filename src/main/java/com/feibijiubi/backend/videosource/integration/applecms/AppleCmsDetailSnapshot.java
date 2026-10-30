package com.feibijiubi.backend.videosource.integration.applecms;

import java.util.List;

/**
 * E2-B：上游 {@code ?ac=detail&ids=<id>} 一条作品的只读标准化快照。
 *
 * <p>包含：作品身份、标题/分类快照、作品级时长<b>文本提示</b>、以及按线路分组的地址候选。它<b>不</b>包含
 * 任何可播放结论、任何单集时长、任何剧集身份（见 {@link AppleCmsLineEntry}）。它也不是本地
 * {@code video} 行：是否导入、归属谁、是否可播，都是后续切片的事。</p>
 *
 * <p>{@link #workDurationHint()} 的三个要点：</p>
 * <ul>
 *   <li>它是<b>作品级</b>文本，可能写成 {@code "45分钟"}，也可能是纯数字。本适配器原样保留它（最多 64 字符），
 *       <b>不</b>解释单位、<b>不</b>换算成秒、<b>不</b>除以条目数。</li>
 *   <li>它<b>不是</b>单集时长：一份作品里 3 条播放条目并不代表时长要分成三份。</li>
 *   <li>空字符串、空白、JSON {@code null} 以及非文本非数字的值都得到 {@code null}（未知），绝不变成 {@code 0}。</li>
 * </ul>
 *
 * <p>上游 {@code vod_total}（"总集数"）刻意不读取：探测样本里它是 {@code 1}，而同一次响应里有 3 条播放条目，
 * 说明它不可信。本模型因此不暴露任何"集数"字段，条目数以 {@code playLines} 的实际内容为准。</p>
 *
 * <h2>构造器的校验边界</h2>
 * <p>compact constructor 只做 {@code null}/空串检查与 {@code List.copyOf}。“标题/分类文本长度上限、
 * 线路数 1..16、每线条目 1..1000”这些性质<b>只</b>由 {@link AppleCmsJsonAdapter} 的解析产物保证；
 * 直接 {@code new} 出来的实例<b>不是</b>已验证对象，不允许直接持久化或当可信数据使用。</p>
 *
 * <h2>{@code toString}</h2>
 * <p>显式覆盖：标题、分类名、时长提示都是上游原文，线路键与地址同理（见 {@link AppleCmsPlayLine}）。
 * 覆盖后只输出固定类名、线路条数与脱敏占位符，不输出任何上游文本（也不截断文本冒充脱敏）。</p>
 *
 * @param workIdentity     作品身份（上游 {@code vod_id} 的规范十进制字符串），非空
 * @param title            标题快照，非空，最多 255 字符（长度上限由解析器保证）
 * @param categoryId       上游 {@code type_id}，可为 {@code null}
 * @param categoryName     上游 {@code type_name}，可为 {@code null}
 * @param workDurationHint 作品级时长文本提示，未知为 {@code null}
 * @param playLines        播放线路，1..16 条，不可变；每条线路 1..1000 条分集条目
 */
public record AppleCmsDetailSnapshot(
        String workIdentity,
        String title,
        String categoryId,
        String categoryName,
        String workDurationHint,
        List<AppleCmsPlayLine> playLines) {

    public AppleCmsDetailSnapshot {
        if (workIdentity == null || workIdentity.isEmpty()) {
            throw new NullPointerException("workIdentity 不能为空");
        }
        if (title == null || title.isEmpty()) {
            throw new NullPointerException("title 不能为空");
        }
        playLines = List.copyOf(playLines);
        if (playLines.isEmpty()) {
            throw new IllegalArgumentException("详情必须至少有一条播放线路");
        }
    }

    /** 不含上游文本、线路键与地址的固定摘要，见类注释。 */
    @Override
    public String toString() {
        return "AppleCmsDetailSnapshot[workIdentity=<已脱敏>, title=<已脱敏>"
                + ", categoryId=<已脱敏>, categoryName=<已脱敏>"
                + ", workDurationHint=<已脱敏>"
                + ", playLineCount=" + playLines.size()
                + "]";
    }
}
