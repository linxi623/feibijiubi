package com.feibijiubi.backend.videosource.integration.transport;

import org.apache.http.protocol.HttpContext;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.SSLInitializationException;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.impl.conn.DefaultRoutePlanner;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * E2-C2：受限客户端的唯一真实构造实现 —— <b>DNS 钩子直接交给连接管理器</b>，并且只走 HTTPS（不经 HTTP 代理路由）。
 *
 * <h2>为什么是"注入管理器"而不是"给 builder 配 dns"</h2>
 * <p>{@code HttpClientBuilder#setDnsResolver} 只影响 builder 自己创建的连接管理器；一旦换成自定义/自建
 * 管理器，这个设置就可能悄悄失效，于是"检查过的地址"和"实际连上的地址"不是同一个（DNS rebinding /
 * TOCTOU）。所以这里显式构造 {@link BasicHttpClientConnectionManager}，把
 * {@link ValidatedDnsResolver} 的包装结果作为<b>构造参数第四项</b>传进去，再由
 * {@code setConnectionManager} 交给 builder。连接期解析就发生在这一处，没有第二次解析。</p>
 *
 * <h2>固定配置（每一项都是刻意关掉的能力）</h2>
 * <ul>
 *   <li><b>只注册 HTTPS</b>：socket 工厂注册表里没有 {@code http} 条目，scheme 不会被顺手降级。</li>
 *   <li><b>TLS 用默认信任链 + 严格主机名校验</b>：{@code SSLContexts.createDefault()}（JVM 默认信任库）
 *       配 {@link DefaultHostnameVerifier}。没有 trust-all {@code TrustStrategy}、没有
 *       {@code NoopHostnameVerifier}、没有自定义 {@code X509HostnameVerifier}。socket 工厂只覆盖
 *       {@code createSocket(HttpContext)} 一个方法（只改原始 socket 的代理语义），
 *       {@code connectSocket}/{@code createLayeredSocket}/TLS 套接字创建/主机名校验仍全部走库的实现，
 *       本类不自己 {@code prepareSocket}。库的 {@code connectSocket} 在 raw TCP 建连后调用
 *       {@code createLayeredSocket(socket, HttpHost.getHostName(), port, context)}，再由
 *       {@code SSLSocketFactory.createSocket(socket, host, port, true)} 分层，所以原始 host 是
 *       <b>结构上</b>交给 TLS 的，本类不需要自己调 {@code setServerNames}。<b>结构上</b>不等于验证过：
 *       真实 SNI、证书校验与握手行为仍需联网集成测试，本切片没有做。</li>
 *   <li><b>不池化</b>：{@link BasicHttpClientConnectionManager} 只持有一条连接，且
 *       {@link RestrictedCmsHttpClient} 每个请求新建并关闭一个客户端，因此不存在"复用一条在 DNS 校验
 *       之前建立的连接"来绕过本次校验。</li>
 *   <li><b>不读 HTTP 代理</b>：显式 {@link DefaultRoutePlanner}（其 {@code determineProxy} 返回
 *       {@code null}），不读 {@code http.proxyHost} 之类的代理环境；也不调用
 *       {@code HttpClientBuilder#useSystemProperties}。这条只覆盖 <b>HTTP 代理路由</b>：JVM 级
 *       {@code socksProxyHost}/{@code socksProxyPort} 默认是由 socket 的创建方式带出来的，路由计划器
 *       管不到。所以原始 TCP socket 由 {@link #newSslSocketFactory()} 里的覆盖显式建成
 *       {@code new Socket(Proxy.NO_PROXY)}（见下）。这是<b>结构事实</b>：底层 socket 不接受 SOCKS
 *       默认代理。它仍<b>不</b>保证出口真的直连 —— 透明代理、NAT、云侧/运营商出口设备不在本类可见
 *       范围内。</li>
 *   <li><b>不跟随重定向、不自动重试、不管 cookie、不缓存认证</b>，并且
 *       {@link RequestConfig#isAuthenticationEnabled()} 为 {@code false}，
 *       {@link RequestConfig#isContentCompressionEnabled()} 为 {@code false}（这样客户端也不会自动加
 *       {@code Accept-Encoding: gzip}）。</li>
 *   <li><b>不重编码 base 路径</b>：{@code setNormalizeUri(false)}，请求行里的 path 就是批准 base 的
 *       raw path。</li>
 * </ul>
 *
 * <h2>不承诺什么</h2>
 * <p>本类不做任何 socket/DNS 验证，也不构成 SSRF 防护验收：它把"地址是否公网候选"的判断完全交给注入的
 * {@link ValidatedDnsResolver}，把"host 是否在允许列表"交给 {@code ExternalVideoUrlPolicy}。外部
 * Apache 组件自身的 wire/header debug 日志（例如把 {@code org.apache.http.wire} 调到 DEBUG）不受本类控制，
 * 那属于部署侧日志配置，不是本类的脱敏保证。版本 4.5.13 与 COS 的传递依赖一致，不代表安全审计通过。</p>
 *
 * <p>异常边界：本地 {@link SSLInitializationException}（默认 SSL 上下文建不起来）折叠成
 * {@link CmsHttpRequestException.Reason#CLIENT_INIT_FAILED}（固定文案、无 cause）；建客户端时抛出的其它
 * 运行时异常照原样上抛，由 {@link RestrictedCmsHttpClient} 统一折叠成
 * {@link CmsHttpRequestException.Reason#TRANSPORT_FAILED}。这里<b>不</b>承诺本地初始化故障全能分类，
 * 也不把外部响应体带进任何失败码。</p>
 *
 * <h2>同包测试 seam：注入 TLS 上下文（E2-C3）</h2>
 * <p>{@link #withSslContext(SSLContext)} 只换<b>信任材料</b>这一项：本机临时 TLS 集成测试用它把客户端信任
 * 范围收窄到测试临时自签证书，从而在真实 socket 上验证握手、主机名校验、跳转/代理/超限行为。生产单例
 * {@link #INSTANCE} 仍然用 {@code SSLContexts.createDefault()}（JVM 默认信任库）配
 * {@link DefaultHostnameVerifier}，因此<b>没有</b>任何公共的“放宽信任”入口：这个 seam 是包私有的，
 * {@link RestrictedCmsHttpClient} 的公开构造器只拿 {@link #INSTANCE}。</p>
 * <p>除信任材料之外的所有行为都共用同一份实现（socket 工厂注册表、{@code Proxy.NO_PROXY} 的原始 socket、
 * 连接管理器构造、DNS 钩子、路由计划器、请求配置、客户端开关），所以测试验证的就是生产构造路径本身，
 * 不是一份复制的 HTTP 实现。</p>
 */
final class RestrictedHttpClientFactory implements CmsHttpClientFactory {

    /** 单例：无可变状态，所有配置都是固定的。 */
    static final RestrictedHttpClientFactory INSTANCE = new RestrictedHttpClientFactory();

    /** 建连上限（毫秒）：固定值，不从配置读。 */
    static final int CONNECT_TIMEOUT_MILLIS = 5_000;

    /** 单次 socket 读上限（毫秒）：卡住的阻塞读只能靠它结束，总预算检查不能打断它。 */
    static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    /** 从（非池化）管理器申请连接的上限（毫秒）：单连接管理器下只是形式约束。 */
    static final int CONNECTION_REQUEST_TIMEOUT_MILLIS = 1_000;

    private static final String HTTPS_SCHEME = "https";

    /**
     * 注入的 TLS 上下文；{@code null} 表示“生产默认信任链”，在每次建 socket 工厂时解析
     * {@code SSLContexts.createDefault()}（保持既有的 {@code CLIENT_INIT_FAILED} 折叠行为）。
     */
    private final SSLContext sslContext;

    private RestrictedHttpClientFactory() {
        this.sslContext = null;
    }

    private RestrictedHttpClientFactory(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    /**
     * 同包测试 seam：返回一个只替换 TLS 信任材料的工厂，其余行为与 {@link #INSTANCE} 完全相同。
     *
     * <p>只给本机临时 TLS 集成测试用（信任范围收窄到测试临时自签证书）。它不是公共 API，也不接受
     * {@code null}：{@code null} 会被读成“用默认信任链”，那样测试就会在不自知的情况下连真实信任库。</p>
     *
     * @param sslContext 只含测试临时证书的上下文（服务端与客户端都不做 trust-all / Noop 校验）
     */
    static RestrictedHttpClientFactory withSslContext(SSLContext sslContext) {
        if (sslContext == null) {
            throw new IllegalArgumentException("注入的 TLS 上下文不能为空：默认信任链由生产单例提供");
        }
        return new RestrictedHttpClientFactory(sslContext);
    }

    @Override
    public CloseableHttpClient newClient(DnsResolver apacheDnsResolver) {
        BasicHttpClientConnectionManager connectionManager = createConnectionManager(apacheDnsResolver);
        try {
            return buildClient(connectionManager);
        } catch (RuntimeException e) {
            // 客户端没建起来就不要留下一个没人关的连接管理器；异常本身照原样抛出（构造期问题不是上游失败）。
            connectionManager.shutdown();
            throw e;
        }
    }

    /**
     * 把一个已校验的 DNS 解析器包装成 Apache 的 {@link DnsResolver}。
     *
     * <p>包装层只做一件事：把固定原因码的 {@link DnsResolutionException} 转换成<b>不带 message、不带
     * cause</b> 的 {@link UnknownHostException}，让 Apache 的连接代码能当作"解析失败"处理。原始 host 不回填、
     * 不打日志、不反查（{@code getHostName} 一律不调用）。</p>
     *
     * @param validatedDnsResolver 已校验的解析器，不允许为 {@code null}
     */
    static DnsResolver apacheDnsResolver(ValidatedDnsResolver validatedDnsResolver) {
        if (validatedDnsResolver == null) {
            throw new IllegalArgumentException("DNS 校验器不能为空：本类没有默认解析实现");
        }
        return new ValidatingDnsResolver(validatedDnsResolver);
    }

    /**
     * 构造一个只走 HTTPS、单一连接、使用给定 DNS 钩子的连接管理器（生产单例的信任材料）。
     *
     * @param apacheDnsResolver 必须是 {@link #apacheDnsResolver(ValidatedDnsResolver)} 的结果（或等价实现）；
     *                          不允许为 {@code null}
     */
    static BasicHttpClientConnectionManager newConnectionManager(DnsResolver apacheDnsResolver) {
        return INSTANCE.createConnectionManager(apacheDnsResolver);
    }

    /**
     * 实例版连接管理器构造：{@link #INSTANCE} 与 {@link #withSslContext(SSLContext)} 共用这一份实现，
     * 唯一的差别是本实例持有的 {@link #sslContext}。
     *
     * @param apacheDnsResolver 已包装 {@link ValidatedDnsResolver} 的解析钩子，不允许为 {@code null}
     */
    BasicHttpClientConnectionManager createConnectionManager(DnsResolver apacheDnsResolver) {
        if (apacheDnsResolver == null) {
            throw new IllegalArgumentException("Apache DnsResolver 不能为空：本类没有默认解析实现");
        }
        Registry<ConnectionSocketFactory> socketFactories = RegistryBuilder.<ConnectionSocketFactory>create()
                .register(HTTPS_SCHEME, newSslSocketFactory())
                .build();
        return new BasicHttpClientConnectionManager(
                socketFactories,
                ManagedHttpClientConnectionFactory.INSTANCE,
                DefaultSchemePortResolver.INSTANCE,
                apacheDnsResolver);
    }

    /** 固定的请求配置：超时、无重定向、无认证、无压缩、不规范化 URI。 */
    static RequestConfig newRequestConfig() {
        return RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setSocketTimeout(SOCKET_TIMEOUT_MILLIS)
                .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MILLIS)
                .setRedirectsEnabled(false)
                .setAuthenticationEnabled(false)
                .setContentCompressionEnabled(false)
                .setExpectContinueEnabled(false)
                .setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                .setNormalizeUri(false)
                .build();
    }

    private static CloseableHttpClient buildClient(HttpClientConnectionManager connectionManager) {
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(newRequestConfig())
                // 禁 HTTP 代理路由：默认路由计划器可能读系统代理设置，这里换成永远不返回代理的那个。
                // 注意：它管不到 JVM 级 SOCKS 默认，那一层由 socket 工厂的 createSocket 覆盖处理。
                .setRoutePlanner(new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE))
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableAutomaticRetries()
                .disableContentCompression()
                .build();
    }

    /**
     * 默认信任链（或本实例注入的 TLS 上下文）+ 严格主机名校验，外加一个<b>只改一条</b>的结构覆盖：原始 TCP
     * socket 用 {@code new Socket(Proxy.NO_PROXY)} 建，不落回 JVM 全局 SOCKS 默认。
     *
     * <p>为什么需要覆盖：4.5.13 的 {@code connectSocket} 在拿到 {@code null} socket 时会调用
     * {@code createSocket(context)}，库的默认实现用的是 {@code SocketFactory.getDefault().createSocket()}，
     * 它会带出 JVM 级 {@code socksProxyHost}/{@code socksProxyPort} 的默认代理；而
     * {@link DefaultRoutePlanner} 只拒绝 HTTP 代理路由，管不到这一层。</p>
     *
     * <p>覆盖范围刻意最小：只换原始 socket 的创建，不碰 {@code connectSocket} /
     * {@code createLayeredSocket}、不碰 TLS 套接字创建与主机名校验，也不自己 {@code prepareSocket}。</p>
     */
    private SSLConnectionSocketFactory newSslSocketFactory() {
        try {
            SSLContext effectiveSslContext = sslContext != null ? sslContext : SSLContexts.createDefault();
            return new SSLConnectionSocketFactory(effectiveSslContext, new DefaultHostnameVerifier()) {
                @Override
                public Socket createSocket(HttpContext context) {
                    // 显式拒绝代理：这是“不让 SOCKS 默认接管”，不是“已证明出口直连”。
                    return new Socket(Proxy.NO_PROXY);
                }
            };
        } catch (SSLInitializationException e) {
            // 本地 SSL 初始化失败：文案/cause 可能带信任库路径等环境细节，折叠成独立的固定码，不带 cause。
            throw new CmsHttpRequestException(CmsHttpRequestException.Reason.CLIENT_INIT_FAILED);
        }
    }

    /** 把已校验的解析器接到 Apache 的连接期解析钩子上。 */
    private static final class ValidatingDnsResolver implements DnsResolver {

        private final ValidatedDnsResolver validatedDnsResolver;

        private ValidatingDnsResolver(ValidatedDnsResolver validatedDnsResolver) {
            this.validatedDnsResolver = validatedDnsResolver;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            try {
                return validatedDnsResolver.resolve(host);
            } catch (DnsResolutionException e) {
                // 固定原因码是给本项目的；Apache 只认 UnknownHostException。空 message、无 cause：
                // 不把 host/地址/原始异常写进任何会被广泛打印的文本。
                throw new UnknownHostException();
            }
        }
    }
}
