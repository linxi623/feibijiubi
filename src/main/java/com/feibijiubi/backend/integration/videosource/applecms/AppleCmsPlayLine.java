package com.feibijiubi.backend.integration.videosource.applecms;

import java.util.List;

/**
 * E2-B：一条播放线路（上游 {@code vod_play_from} 的一段 + 对应的 {@code vod_play_url} 一段）。
 *
 * <p>线路身份 = {@link #lineKey()}（上游线路标识，逐字保留、不裁剪、不做同名合并）。线路键里的
 * {@code gsyun}/{@code gsm3u8} 只是上游的命名，<b>不</b>证明格式或可播放性，见
 * {@link AppleCmsPlaybackState}。</p>
 *
 * <p>{@link #snapshotOrdinal()} 是这份快照里线路出现的位次（1 起），仅供展示与排查，<b>不是</b>线路 ID；
 * 上游调换顺序时它会指向另一条线路。跨线路不自动融合：不同线路的同名标签仍然是各自的条目，本适配器
 * 不按标签合并、不去重、不"就近匹配"。</p>
 *
 * <p>{@code toString} 显式覆盖：默认实现会把 {@code lineKey} 原样拼进去，而线路键是上游原文（最多 191 字符），
 * 上游可以把 URL/签名塞进线路键；条目本身也不输出地址（见 {@link AppleCmsLineEntry#toString()}）。
 * 覆盖后只输出固定类名、{@code snapshotOrdinal}、条目数与脱敏占位符。</p>
 *
 * <h2>构造器的校验边界</h2>
 * <p>compact constructor 只做 {@code null}/空串/序号范围检查与 {@code List.copyOf}。“线路键最多 191 字符、
 * 无首尾空白、条目数 1..1000”这些性质<b>只</b>由 {@link AppleCmsJsonAdapter} 的解析产物保证；直接
 * {@code new} 出来的实例<b>不是</b>已验证对象，不允许直接持久化或当可信数据使用。</p>
 *
 * @param snapshotOrdinal 本线路在快照中的位次（1 起），仅展示用
 * @param lineKey         上游线路键，非空、无首尾空白、最多 191 字符（这些约束由解析器保证）
 * @param entries         该线路的分集条目，1..1000 条，不可变
 */
public record AppleCmsPlayLine(int snapshotOrdinal, String lineKey, List<AppleCmsLineEntry> entries) {

    public AppleCmsPlayLine {
        if (snapshotOrdinal < 1) {
            throw new IllegalArgumentException("snapshotOrdinal 从 1 开始");
        }
        if (lineKey == null || lineKey.isEmpty()) {
            throw new NullPointerException("lineKey 不能为空");
        }
        entries = List.copyOf(entries);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("线路必须至少有一条分集条目");
        }
    }

    /** 不含线路键与地址的固定摘要，见类注释。 */
    @Override
    public String toString() {
        return "AppleCmsPlayLine[snapshotOrdinal=" + snapshotOrdinal
                + ", lineKey=<已脱敏>"
                + ", entryCount=" + entries.size()
                + "]";
    }
}
