package com.feibijiubi.backend.videosource.vo;

import lombok.Getter;

import java.util.List;

/**
 * M6-A 播放响应。
 *
 * <p>只有<b>选中的那一条</b>线路的 {@code url} 含上游媒体地址；{@code lines[]} 只给本地 binding id、
 * 标签与按配置计算的可用性，不含地址、摘要、来源配置或凭据引用。不可播放时 {@code url/lineId/
 * episodeLabel/format} 均为 null，{@code reason} 是固定码。</p>
 *
 * <p><b>刻意不生成 {@code toString()}</b>：{@code url} 可能是带签名的上游播放凭据，不能因为日志或异常
 * 打印整个对象而泄露。需要调试时只打印 vid/playable/reason。</p>
 */
@Getter
public class VideoPlaybackVO {

    private final Integer vid;
    private final boolean playable;
    private final String reason;
    private final String format;
    private final String url;
    private final Long lineId;
    private final String episodeLabel;
    private final List<Line> lines;

    public VideoPlaybackVO(Integer vid, boolean playable, String reason, String format,
                           String url, Long lineId, String episodeLabel, List<Line> lines) {
        this.vid = vid;
        this.playable = playable;
        this.reason = reason;
        this.format = format;
        this.url = url;
        this.lineId = lineId;
        this.episodeLabel = episodeLabel;
        this.lines = List.copyOf(lines);
    }

    /** 其他线路的安全投影：本地 id、标签、按配置是否可用；没有媒体地址。 */
    public record Line(long lineId, String episodeLabel, boolean available) {
    }
}
