package com.feibijiubi.backend.integration.videosource.transport;

import com.feibijiubi.backend.integration.videosource.ExternalVideoUrlPolicy;

import java.net.URI;

/**
 * M2-A：<b>受限的单次 HLS 媒体清单 GET</b> —— 一次调用只取一份清单字节，交给 M1 的离线解析器。
 * 没有 Spring 注解、不读配置、不注册调度、<b>没有生产调用方</b>。
 *
 * <h2>它能请求什么</h2>
 * <ul>
 *   <li><b>只有一种形态</b>：{@link #fetchManifest(String)} 对一个调用方给出的候选地址发一次 {@code GET}。
 *       没有"任意 URL + 任意 method/header"的入口，没有 HEAD 探测、没有递归、没有再取分片/密钥/初始化段，
 *       也不下载任何媒体内容。</li>
 *   <li><b>地址由调用方传入的策略判定</b>：{@link ExternalVideoUrlPolicy} 必须<b>单独</b>为媒体用途构造
 *       （精确 host:port 允许列表 + 长度上限）。本类<b>不会</b>从 CMS 的 base 合并 host，也不会在策略拒绝后
 *       退回"默认允许"：媒体端点是否被授权由部署配置显式给出。</li>
 *   <li><b>原始 URI 原样发出</b>：策略返回的 {@link URI}（{@code toString()} 与入参完全一致）直接交给
 *       {@code HttpGet}，本类不重编码、不规范化、不重排 query，因此签名 query（{@code %2F}、{@code %3B}、
 *       {@code +}、参数顺序）逐字保留。请求行里的 raw path 也来自这条 URI（工厂已
 *       {@code setNormalizeUri(false)}）。</li>
 *   <li><b>路径契约</b>：候选地址必须显式带路径（root {@code /} 合法），且以<b>单个</b> {@code /} 开头。
 *       host-only 与 {@code //} 开头的地址在构造请求前拒绝 —— 本类不替调用方补写或猜测路径。</li>
 *   <li><b>固定 header</b>：只有 {@code Accept: application/vnd.apple.mpegurl, application/x-mpegURL} 与
 *       {@code Accept-Encoding: identity}。没有 Cookie/Authorization/Referer，也不接受调用方追加 header。</li>
 *   <li><b>扩展名与 Content-Type 都不是身份保证</b>：本类不看后缀、不看 Content-Type，也不据此判断"这是不是
 *       清单"。一份 200 的字节是不是可解析的媒体清单，由 {@link com.feibijiubi.backend.integration.videosource.media.HlsMediaDurationParser}
 *       决定；本类只保证字节是<b>这一次请求</b>取回的、有界的。</li>
 * </ul>
 *
 * <h2>执行与清理</h2>
 * <p>发送、200/Content-Encoding/空 body/512 KiB 上限/协作式 20 s 预算的判定、有界读取以及
 * {@code abort → client.close → body.close → response.close} 的关闭顺序<b>全部</b>由同包的
 * {@link RestrictedGetExecutor} 实现 —— 与 {@link RestrictedCmsHttpClient} 是<b>同一份</b>代码，不是复制品。
 * 因此两条链路的重定向不跟随、不解压、不自动重试、DNS 连接期校验、严格 TLS、{@code Proxy.NO_PROXY} 行为
 * 完全一致（见 {@link RestrictedHttpClientFactory}）。</p>
 *
 * <h2>失败与脱敏</h2>
 * <ul>
 *   <li>地址不被策略接受：{@link ExternalVideoUrlPolicy.RejectedUrlException} 原样上抛（固定原因码，
 *       不含 URL/host/query）。</li>
 *   <li>路径契约不满足：{@link IllegalArgumentException}，文案固定且不含 URL。</li>
 *   <li>上游失败：{@link CmsHttpRequestException} 原样上抛。这里沿用的是<b>历史命名</b>的 CMS 传输异常 ——
 *       M2-A 起它与媒体清单客户端共用同一套固定原因码，本切片<b>不</b>新增平行的异常层，也不改变任何
 *       原因码的含义。</li>
 * </ul>
 *
 * <h2>它不证明什么</h2>
 * <p>地址允许列表只证明"这个端点被授权请求"，<b>不</b>证明内容被授权、清单内容正确、分片可访问、时长真实，
 * 也不构成完整 SSRF 防护（逐跳重定向策略、部署侧出口限制不在本类范围）。返回的 {@code byte[]} 只是
 * 候选值解析的输入。</p>
 *
 * <h2>线程安全</h2>
 * <p>不可变（两个字段都是构造期赋值的 {@code final}），每次调用各自新建并关闭一个客户端，不共享
 * per-request 状态，因此可以被多个线程共享。</p>
 */
public final class RestrictedManifestHttpClient {

    /**
     * 媒体清单请求固定的 {@code Accept}：只声明两种 HLS 清单类型，不声明任何播放/下载类型。
     *
     * <p>它<b>不是</b>身份判定：上游返回什么 Content-Type、地址带什么扩展名，都不改变本类"把字节交给解析器"
     * 的行为。</p>
     */
    private static final String ACCEPT_HLS_MANIFEST = "application/vnd.apple.mpegurl, application/x-mpegURL";

    private static final String PATH_REQUIRED_MESSAGE =
            "媒体清单地址必须显式带路径（root '/' 合法）：本客户端不会自动补写 '/'";
    private static final String PATH_PREFIX_MESSAGE = "媒体清单地址的路径必须以单个 '/' 开头";

    private final ExternalVideoUrlPolicy mediaUrlPolicy;
    private final RestrictedGetExecutor executor;

    /**
     * 生产构造器：真实受限客户端 + 系统时钟。
     *
     * @param mediaUrlPolicy 媒体用途的地址策略（HTTPS + 精确 host:port 允许列表 + 长度上限），不允许为
     *                       {@code null}：本类<b>没有</b>默认策略，也不会复用 CMS 端点的允许列表
     * @param dnsResolver    已校验的解析器，不允许为 {@code null}：本类没有默认解析实现
     * @throws IllegalArgumentException 任一参数为 {@code null}
     */
    public RestrictedManifestHttpClient(ExternalVideoUrlPolicy mediaUrlPolicy,
                                        ValidatedDnsResolver dnsResolver) {
        this(mediaUrlPolicy, dnsResolver, CmsRequestDeadline.SYSTEM_CLOCK, RestrictedHttpClientFactory.INSTANCE);
    }

    /**
     * 离线单测用构造器：注入受控时钟与客户端工厂。包私有 —— 生产路径不会经过它，所以无法用假的工厂绕过
     * DNS 校验或 TLS 配置（见 {@link CmsHttpClientFactory}）。
     */
    RestrictedManifestHttpClient(ExternalVideoUrlPolicy mediaUrlPolicy,
                                 ValidatedDnsResolver dnsResolver,
                                 CmsRequestDeadline.Clock clock,
                                 CmsHttpClientFactory clientFactory) {
        if (mediaUrlPolicy == null) {
            throw new IllegalArgumentException("媒体地址策略不能为空：本类不会复用 CMS 端点的允许列表");
        }
        if (dnsResolver == null) {
            throw new IllegalArgumentException("DNS 校验器不能为空：本类没有默认解析实现");
        }
        if (clock == null) {
            throw new IllegalArgumentException("时间源不能为空");
        }
        if (clientFactory == null) {
            throw new IllegalArgumentException("客户端工厂不能为空");
        }
        this.mediaUrlPolicy = mediaUrlPolicy;
        this.executor = new RestrictedGetExecutor(dnsResolver, clock, clientFactory);
    }

    /**
     * 取一份 HLS 清单的原始字节：<b>一次调用 = 一次请求</b>，不重试、不跟随、不再取任何后续资源。
     *
     * @param candidateUrl 候选媒体清单地址；必须通过 {@code mediaUrlPolicy}、显式带路径且以单个 {@code /}
     *                     开头
     * @return 长度在 {@code 1..512 KiB} 之间的响应体副本，交给离线解析器；<b>不</b>表示这是合法清单
     * @throws ExternalVideoUrlPolicy.RejectedUrlException 地址不被策略接受；固定原因码，不含 URL
     * @throws IllegalArgumentException                    地址缺少路径或以 {@code //} 开头；此时不会创建客户端
     * @throws CmsHttpRequestException                     上游失败；只带固定原因码（与非 200 的状态整数）
     */
    public byte[] fetchManifest(String candidateUrl) {
        return executor.execute(requireManifestUri(candidateUrl), ACCEPT_HLS_MANIFEST);
    }

    /**
     * 用调用方策略校验候选地址，并落实本类自己的路径契约。
     *
     * <p>策略返回的 URI 与入参完全一致，所以这里不做任何改写：返回的对象就是将要发出的请求目标。</p>
     */
    private URI requireManifestUri(String candidateUrl) {
        URI target = mediaUrlPolicy.validate(candidateUrl);
        String rawPath = target.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            throw new IllegalArgumentException(PATH_REQUIRED_MESSAGE);
        }
        if (rawPath.charAt(0) != '/' || rawPath.startsWith("//")) {
            throw new IllegalArgumentException(PATH_PREFIX_MESSAGE);
        }
        return target;
    }
}
