package com.feibijiubi.backend.integration.videosource.applecms;

/**
 * E2-B：一条上游线路地址的<b>可播放性状态</b>。
 *
 * <p>当前只有一个取值 {@link #UNVERIFIED}：本适配器只做语法与允许端点校验，<b>不</b>下载、<b>不</b>探测、
 * <b>不</b>解析清单。因此线路键里的 {@code gsyun}、{@code gsm3u8} 之类的字样，以及地址里的 {@code .m3u8}
 * 后缀，都<b>不</b>构成"可播放""HLS 格式""MP4 格式"的证据——它们只是上游自己起的名字，可能指页面、
 * 可能指清单、也可能已经失效。</p>
 *
 * <p>取值刻意只有一个：不存在 {@code PLAYABLE}，所以调用方无法从本模型推断出"已经可以播"。要变成可播放，
 * 必须另有独立、经批准且带安全校验的探测/清单解析切片，并把结论写入独立状态，不能在解析器里顺手置位。</p>
 */
public enum AppleCmsPlaybackState {

    /** 仅记录了线路键与原始地址候选；格式、时长、可播放性、可用性全部未验证。 */
    UNVERIFIED
}
