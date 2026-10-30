package com.feibijiubi.backend.videosource.integration.applecms;

/**
 * E2-B：一条线路里的一个分集条目（标签 + 地址候选）。
 *
 * <h2>明确"没有"的东西</h2>
 * <ul>
 *   <li><b>没有稳定剧集身份</b>：探测样本里上游不提供独立 {@code episodeId}，本类因此<b>不</b>声明任何
 *       {@code episodeId}/{@code identity}/{@code vid} 访问器——不存在"顺手拿线路键或序号当身份"的可能。
 *       这些条目必须经过人工映射（或将来另行批准的匹配协议）才能绑定本地 {@code vid}。</li>
 *   <li><b>没有单集时长</b>：本类不暴露任何秒数字段。{@code vod_duration} 是作品级文本提示
 *       （{@link AppleCmsDetailSnapshot#workDurationHint()}），可能是 {@code "45分钟"}、可能为空，也可能
 *       与真实条目数矛盾，把它的数字当成"每集秒数"是伪造数据。</li>
 *   <li><b>没有"可播放"结论</b>：{@link #playbackState()} 恒为 {@link AppleCmsPlaybackState#UNVERIFIED}。</li>
 * </ul>
 *
 * <h2>{@code snapshotOrdinal}</h2>
 * <p>只是"这份快照里这一条出现在第几位"（从 1 开始），用于展示与排查。<b>禁止</b>把它当作剧集业务 ID 或
 * 持久键：上游调整一次顺序，它就会指向另一条。</p>
 *
 * <h2>{@code toString}</h2>
 * <p>显式覆盖：默认 {@code toString} 会把 {@code address} 原样拼进去，而地址可能带签名 query；{@code episodeLabel}
 * 同样是上游原文（最多 255 字符），上游完全可以把 URL 或凭据塞进标签。所以两者都不输出，只保留固定的
 * 类名、{@code snapshotOrdinal} 与 {@code playbackState}。
 * <b>不使用</b>“截断文本”充当脱敏：截断后的前缀仍然是上游原文。</p>
 *
 * <h2>构造器的校验边界</h2>
 * <p>compact constructor 只做 {@code null}/空串/序号范围检查。“标签最多 255 字符、地址最多 2048 字符、
 * 地址已通过 {@code ExternalVideoUrlPolicy}”这些性质<b>只</b>由 {@link AppleCmsJsonAdapter} 的解析产物保证；
 * 直接 {@code new} 出来的实例<b>不是</b>已验证对象，不允许直接持久化或当可信数据使用。本 record 不重复
 * 实现长度/URL 校验（那会把解析器常量复制一份，两处规则迟早分叉）。</p>
 *
 * @param snapshotOrdinal 该条目在本线路快照中的位置（1 起），仅展示用
 * @param episodeLabel    上游分集标签，非空，最多 255 字符；同名标签允许重复，本适配器不按标签去重
 * @param address         原始地址候选（解析产物里已通过 {@code ExternalVideoUrlPolicy} 的语法与允许端点校验），
 *                        逐字保留，不做任何改写/重新编码
 * @param playbackState   可播放性状态，当前恒为 {@link AppleCmsPlaybackState#UNVERIFIED}
 */
public record AppleCmsLineEntry(
        int snapshotOrdinal,
        String episodeLabel,
        String address,
        AppleCmsPlaybackState playbackState) {

    public AppleCmsLineEntry {
        if (snapshotOrdinal < 1) {
            throw new IllegalArgumentException("snapshotOrdinal 从 1 开始");
        }
        if (episodeLabel == null || episodeLabel.isEmpty()) {
            throw new NullPointerException("episodeLabel 不能为空");
        }
        if (address == null || address.isEmpty()) {
            throw new NullPointerException("address 不能为空");
        }
        if (playbackState == null) {
            throw new NullPointerException("playbackState 不能为空");
        }
    }

    /** 不含上游文本与地址的固定摘要，见类注释。 */
    @Override
    public String toString() {
        return "AppleCmsLineEntry[snapshotOrdinal=" + snapshotOrdinal
                + ", episodeLabel=<已脱敏>"
                + ", address=<已脱敏>"
                + ", playbackState=" + playbackState
                + "]";
    }
}
