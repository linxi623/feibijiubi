package com.feibijiubi.backend.videosource.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 播放解析用的服务端联表行：一条 {@code local_episode_source_binding} 加上它当前指针所指的候选。
 *
 * <p>只由 M6-A 的只读播放查询构造。<b>刻意不生成 {@code toString()}</b>：{@code address} 是可能带
 * 签名的上游播放凭据，不能因为日志或异常打印整个对象而泄露。也不要把本类作为响应体。</p>
 */
@Getter
@Setter
public class LocalEpisodePlaybackLine {
    private Long bindingId;
    private Long candidateId;
    private String sourceCode;
    private String label;
    private String address;
    private String addressHash;
    private String probeStatus;
    private Double candidateSeconds;
    private String manifestHash;
    private Double confirmedSeconds;
    private LocalDateTime confirmedAt;
}
