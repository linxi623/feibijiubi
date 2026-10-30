package com.feibijiubi.backend.videosource.integration.media;

import com.feibijiubi.backend.videosource.integration.transport.RestrictedManifestHttpClient;

/**
 * M2-A：<b>一次受限清单获取 + 一次 M1 解析</b>的组合器 —— 只把"取字节"和"算候选时长"接起来。
 *
 * <h2>一次调用只做两步</h2>
 * <ol>
 *   <li><b>取字节</b>：调用 {@link RestrictedManifestHttpClient#fetchManifest(String)}，请求形态（媒体端点
 *       允许列表、原始 URI 逐字发出、固定 {@code Accept}、200/identity/512 KiB/20 s 协作式预算、不跟随
 *       重定向、不重试）全部由它决定，本类<b>不</b>拼 URL、不设 header。</li>
 *   <li><b>解析</b>：把拿到的 {@code byte[]} 交给 {@link HlsMediaDurationParser#parse(byte[])}，结构、上限与
 *       固定拒绝码全部由它决定。</li>
 * </ol>
 * <p>两次调用都是<b>一次</b>：本类不重试、不追加请求、不取分片/密钥，也没有缓存。</p>
 *
 * <h2>输入地址的来源（这段决定它能不能被当成公开接口）</h2>
 * <p>{@code candidateUrl} <b>必须</b>来自以后批准的管理端候选（M3：管理员显式选择的那条播放线路），
 * <b>不</b>表示"用户可以随便提交一个 URL"。URL 允许列表只证明<b>端点被授权</b>，<b>不</b>证明<b>内容被授权</b>：
 * 同一个被批准的 CDN 上仍可能挂着与本作品无关的内容，来源授权仍需人工核对。本类目前<b>没有</b> Spring 注解、
 * 没有HTTP控制器、日志、状态或数据库写入。S2的RestrictedExternalMediaProbe现已作为生产调用方
 * 接入管理员草稿候选流程；持久化及人工确认由外层服务负责。</p>
 *
 * <h2>返回值仍然只是候选值</h2>
 * <ul>
 *   <li>返回的就是 {@link HlsMediaDurationCandidate} 本身（M1 的记录），本类<b>不</b>新增包装记录，也<b>不</b>
 *       补任何字段：不伪造探测时间、不写入"已确认"、不产生数据库证据、不承诺可播放。</li>
 *   <li>URL 指纹/版本绑定、"这次探测属于哪条线路候选"这类归属信息由后续 M4 的服务侧保存（探测记录绑定
 *       源、作品、候选版本、地址指纹与内容指纹）。本切片只做组合，不替它决定。</li>
 *   <li>MP4 等非 HLS 输入由解析器明确拒绝（首版自动探测不支持 MP4），本类不调用任何外部 {@code ffprobe}，
 *       也不拿文件大小/码率估算充数。</li>
 *   <li>master 清单与直播/未完成清单同样由解析器拒绝，<b>拒绝后不再发任何请求</b>（不自动挑选媒体清单）。</li>
 * </ul>
 *
 * <h2>失败面（谁的原因码就是谁的原因码）</h2>
 * <p>{@link com.feibijiubi.backend.videosource.integration.transport.CmsHttpRequestException}（传输）、
 * {@link com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectedUrlException}（地址策略）、
 * {@link IllegalArgumentException}（路径契约）与 {@link HlsManifestParseException}（解析）全部原样上抛：
 * 本类不捕获、不重新分类、不改写原因码，也不把 URL 或清单原文拼进任何文案。这些异常自身已声明不带 URL、
 * host、响应体与 cause。</p>
 *
 * <h2>线程安全</h2>
 * <p>不可变（唯一字段是构造期赋值的 {@code final}），不持有 per-request 状态；可共享性继承自受限客户端与
 * 纯函数解析器。</p>
 */
public final class HlsDurationProbe {

    private final RestrictedManifestHttpClient manifestHttpClient;

    /**
     * @param manifestHttpClient 受限媒体清单客户端（取字节），不允许为 {@code null}：本类<b>没有</b>默认客户端，
     *                           也不会退化成"直接连上游"
     * @throws IllegalArgumentException 参数为 {@code null}
     */
    public HlsDurationProbe(RestrictedManifestHttpClient manifestHttpClient) {
        if (manifestHttpClient == null) {
            throw new IllegalArgumentException("受限媒体清单客户端不能为空：本类没有默认客户端");
        }
        this.manifestHttpClient = manifestHttpClient;
    }

    /**
     * 取一次清单并返回解析出的候选时长。
     *
     * @param candidateUrl 已批准候选线路的媒体清单地址；一次调用只发一次请求
     * @return 不可变候选值；<b>不是</b>已确认时长，也<b>不</b>表示可播放
     * @throws RuntimeException 传输失败、地址被拒或清单结构不通过时，由对应层抛出固定码异常（原样上抛）
     */
    public HlsMediaDurationCandidate probe(String candidateUrl) {
        return HlsMediaDurationParser.parse(manifestHttpClient.fetchManifest(candidateUrl));
    }
}
