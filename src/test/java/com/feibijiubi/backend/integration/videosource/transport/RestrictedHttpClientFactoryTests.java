package com.feibijiubi.backend.integration.videosource.transport;

import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Lookup;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;

/**
 * E2-C2：{@link RestrictedHttpClientFactory} 的<b>结构接线</b>测试 —— 不发包、不解析真实域名、
 * 不启动 Spring。
 *
 * <h2>钉住的事实</h2>
 * <ul>
 *   <li>连接管理器<b>构造时</b>收到的第四项参数就是包装了 {@link ValidatedDnsResolver} 的那个
 *       {@link DnsResolver}，第一项是只注册了 {@code https} 的 socket 工厂注册表：这是本切片最关键的一条
 *       接线，因为自定义管理器会让 {@code HttpClientBuilder#setDnsResolver} 失效。</li>
 *   <li>解析钩子的行为：校验通过时返回注入查询给出的地址；校验失败时抛出<b>空 message、无 cause</b> 的
 *       {@link UnknownHostException}，不把 host 带出去。</li>
 *   <li><b>原始 socket 的代理语义</b>：注册表里那个 HTTPS 工厂的 {@code createSocket(null)} 走的是
 *       本项目的最小覆盖，构造成 {@code new Socket(Proxy.NO_PROXY)}（用 {@code mockConstruction(Socket.class)}
 *       捕获构造参数），因此 JVM 级 SOCKS 默认（{@code socksProxyHost}）接不进来。这只是结构事实：它
 *       不证明出口真的直连，也不涉及透明代理/NAT。</li>
 *   <li><b>固定失败码</b>：本地 SSL 初始化失败用独立的 {@code CLIENT_INIT_FAILED}，文案固定、无 cause。
 *       这里<b>不</b>模拟 JVM SSL provider 故障，只钉住码与文案。</li>
 *   <li>固定超时与"关掉重定向/认证/压缩/URI 规范化"的 {@link RequestConfig} 数值。</li>
 *   <li>真实工厂能建出一个客户端并正常关闭（构造过程不建 socket）。</li>
 * </ul>
 *
 * <h2>怎么证明"钩子交给了管理器"</h2>
 * <p>{@link BasicHttpClientConnectionManager} 在 4.5.13 里<b>没有</b> {@code getDnsResolver()} /
 * {@code getSocketFactoryRegistry()} 这类 getter（管理器把两者塞进内部的连接 operator，没有对外暴露），
 * 所以这里不去读私有字段，而是用 {@code mockConstruction} 拦截真实的构造调用、把
 * <b>构造参数原样</b>捕获下来断言。这是"接线在构造点成立"的结构证明，不涉及任何私有实现细节。</p>
 *
 * <h2>不证明什么</h2>
 * <p>不证明真实 socket 连到了校验后的地址、不证明 TLS 握手/SNI 行为、不证明 truststore 里没有可疑 CA。
 * “钩子已接上”与“原始 socket 不接受 SOCKS 默认代理”是代码结构事实；真正的连接期行为（含 SNI 与出口是否
 * 真的直连）需要联网环境验证，本切片没有做。</p>
 */
class RestrictedHttpClientFactoryTests {

    @Test
    void handsTheInjectedValidatedDnsHookToTheConnectionManagerConstructor() throws Exception {
        List<String> lookedUpHosts = new ArrayList<>();
        ValidatedDnsResolver validatedDnsResolver = new ValidatedDnsResolver(host -> {
            lookedUpHosts.add(host);
            return new InetAddress[] {InetAddress.getByAddress(new byte[] {1, 1, 1, 1})};
        });
        DnsResolver apacheDnsResolver = RestrictedHttpClientFactory.apacheDnsResolver(validatedDnsResolver);

        List<List<Object>> capturedArguments = new ArrayList<>();
        try (MockedConstruction<BasicHttpClientConnectionManager> construction = mockConstruction(
                BasicHttpClientConnectionManager.class,
                (mock, context) -> capturedArguments.add(new ArrayList<>(context.arguments())))) {

            BasicHttpClientConnectionManager connectionManager =
                    RestrictedHttpClientFactory.newConnectionManager(apacheDnsResolver);

            assertNotNull(connectionManager);
            assertEquals(1, construction.constructed().size(), "只应构造一个连接管理器");
            assertEquals(1, capturedArguments.size());

            List<Object> arguments = capturedArguments.get(0);
            assertEquals(4, arguments.size(), "必须是四参数构造：注册表、连接工厂、端口解析器、DNS 钩子");
            // 第四项就是"连接期解析"真正用的那个钩子：不是 builder 上挂的 dns，而是管理器自己持有的。
            assertSame(apacheDnsResolver, arguments.get(3));
            assertSame(ManagedHttpClientConnectionFactory.INSTANCE, arguments.get(1));
            assertSame(DefaultSchemePortResolver.INSTANCE, arguments.get(2));
        }

        // 接线之外，再独立验证这个钩子本身的行为（与构造过程无关，直接调用捕获到的实现）。
        InetAddress[] resolved = apacheDnsResolver.resolve("cdn.example.com");
        assertEquals(1, resolved.length);
        assertEquals(List.of("cdn.example.com"), lookedUpHosts);
    }

    @Test
    void handsAnHttpsOnlySocketFactoryRegistryToTheConnectionManagerConstructor() {
        DnsResolver apacheDnsResolver = RestrictedHttpClientFactory.apacheDnsResolver(
                new ValidatedDnsResolver(host -> {
                    throw new AssertionError("本用例不解析任何主机名");
                }));

        List<List<Object>> capturedArguments = new ArrayList<>();
        try (MockedConstruction<BasicHttpClientConnectionManager> construction = mockConstruction(
                BasicHttpClientConnectionManager.class,
                (mock, context) -> capturedArguments.add(new ArrayList<>(context.arguments())))) {

            RestrictedHttpClientFactory.newConnectionManager(apacheDnsResolver);

            assertEquals(1, construction.constructed().size());
            assertEquals(1, capturedArguments.size());
            Object registryArgument = capturedArguments.get(0).get(0);
            assertTrue(registryArgument instanceof Lookup);
            @SuppressWarnings("unchecked")
            Lookup<ConnectionSocketFactory> socketFactories = (Lookup<ConnectionSocketFactory>) registryArgument;
            assertTrue(socketFactories.lookup("https") instanceof SSLConnectionSocketFactory);
            assertNull(socketFactories.lookup("http"), "注册表里不允许有 http，否则 scheme 可能被降级");
        }
    }

    @Test
    void rejectedHostBecomesAnUnknownHostExceptionWithoutMessageOrCause() {
        DnsResolver apacheDnsResolver = RestrictedHttpClientFactory.apacheDnsResolver(
                new ValidatedDnsResolver(host -> {
                    throw new AssertionError("被策略拒绝的 host 不应该进入查询实现");
                }));

        UnknownHostException failure = assertThrows(UnknownHostException.class,
                () -> apacheDnsResolver.resolve("localhost"));

        assertNull(failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void requestConfigPinsTimeoutsAndDisablesRedirectAuthenticationAndCompression() {
        RequestConfig requestConfig = RestrictedHttpClientFactory.newRequestConfig();

        assertEquals(5_000, requestConfig.getConnectTimeout());
        assertEquals(10_000, requestConfig.getSocketTimeout());
        assertEquals(1_000, requestConfig.getConnectionRequestTimeout());
        assertFalse(requestConfig.isRedirectsEnabled());
        assertFalse(requestConfig.isAuthenticationEnabled());
        assertFalse(requestConfig.isContentCompressionEnabled());
        assertFalse(requestConfig.isNormalizeUri());
        assertFalse(requestConfig.isExpectContinueEnabled());
        assertEquals(CookieSpecs.IGNORE_COOKIES, requestConfig.getCookieSpec());
    }

    @Test
    void buildsAndClosesARealClientWithoutOpeningAnySocket() throws IOException {
        DnsResolver apacheDnsResolver = RestrictedHttpClientFactory.apacheDnsResolver(
                new ValidatedDnsResolver(host -> {
                    throw new AssertionError("构造客户端不解析任何主机名");
                }));

        CloseableHttpClient client = RestrictedHttpClientFactory.INSTANCE.newClient(apacheDnsResolver);
        try {
            assertNotNull(client);
        } finally {
            client.close();
        }
    }

    @Test
    void rawSocketCreationUsesNoProxyWhileTheLibraryKeepsTlsLayering() throws Exception {
        DnsResolver apacheDnsResolver = RestrictedHttpClientFactory.apacheDnsResolver(
                new ValidatedDnsResolver(host -> {
                    throw new AssertionError("本用例不解析任何主机名");
                }));

        List<List<Object>> managerArguments = new ArrayList<>();
        List<List<Object>> socketArguments = new ArrayList<>();
        try (MockedConstruction<Socket> sockets = mockConstruction(Socket.class,
                (mock, context) -> socketArguments.add(new ArrayList<>(context.arguments())));
             MockedConstruction<BasicHttpClientConnectionManager> construction = mockConstruction(
                     BasicHttpClientConnectionManager.class,
                     (mock, context) -> managerArguments.add(new ArrayList<>(context.arguments())))) {

            RestrictedHttpClientFactory.newConnectionManager(apacheDnsResolver);

            assertEquals(1, managerArguments.size());
            @SuppressWarnings("unchecked")
            Lookup<ConnectionSocketFactory> socketFactories =
                    (Lookup<ConnectionSocketFactory>) managerArguments.get(0).get(0);
            ConnectionSocketFactory https = socketFactories.lookup("https");
            assertTrue(https instanceof SSLConnectionSocketFactory);

            Socket created = https.createSocket(null);

            assertEquals(1, sockets.constructed().size(), "只应构造一个原始 socket");
            assertSame(sockets.constructed().get(0), created);
            assertEquals(1, socketArguments.size());
            assertSame(Proxy.NO_PROXY, socketArguments.get(0).get(0),
                    "原始 socket 必须显式 NO_PROXY，不能落回 JVM 级 SOCKS 默认");
        }
    }

    @Test
    void sslInitializationFailureHasItsOwnFixedCodeWithoutCause() {
        CmsHttpRequestException failure =
                new CmsHttpRequestException(CmsHttpRequestException.Reason.CLIENT_INIT_FAILED);

        assertEquals(CmsHttpRequestException.Reason.CLIENT_INIT_FAILED, failure.reason());
        assertEquals("本地受限客户端初始化失败", CmsHttpRequestException.Reason.CLIENT_INIT_FAILED.description());
        assertEquals(CmsHttpRequestException.Reason.CLIENT_INIT_FAILED.description(), failure.getMessage());
        assertEquals(CmsHttpRequestException.NO_UPSTREAM_STATUS, failure.upstreamStatus());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage()
                .equals(CmsHttpRequestException.Reason.TRANSPORT_FAILED.description()));
    }

    @Test
    void rejectsNullDnsHooks() {
        assertThrows(IllegalArgumentException.class, () -> RestrictedHttpClientFactory.apacheDnsResolver(null));
        assertThrows(IllegalArgumentException.class, () -> RestrictedHttpClientFactory.newConnectionManager(null));
    }
}
