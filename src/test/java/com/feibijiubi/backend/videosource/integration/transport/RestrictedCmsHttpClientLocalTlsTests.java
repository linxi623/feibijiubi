package com.feibijiubi.backend.videosource.integration.transport;

import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy;
import com.feibijiubi.backend.videosource.integration.media.HlsDurationProbe;
import com.feibijiubi.backend.videosource.integration.media.HlsManifestParseException;
import com.feibijiubi.backend.videosource.integration.media.HlsMediaDurationCandidate;
import com.feibijiubi.backend.videosource.integration.transport.CmsHttpRequestException.Reason;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E2-C3：{@link RestrictedCmsHttpClient} 的<b>本机真实 socket / 真实 TLS</b> 集成测试 —— 只在
 * {@code 127.0.0.1} 的随机回环端口上跑，不访问外网、不碰系统信任库、不用数据库/Redis/RabbitMQ/Docker。
 *
 * <h2>默认不执行</h2>
 * <p>整类由 {@code @EnabledIfSystemProperty(named = "videoSourceLocalTls", matches = "true")} 控制，
 * 普通 {@code ./mvnw test} 会跳过它（报告里显示为 skipped）。这是 opt-in 类：它真的会起监听端口、真的会跑
 * {@code keytool}。</p>
 *
 * <h2>它验证什么（真实字节，不是 mock）</h2>
 * <ul>
 *   <li><b>正确证书 + 精确 host 成功</b>：服务端记录到 {@code Host: api.example.invalid:<port>}、固定
 *       path/query，并从 {@code ExtendedSSLSession#getRequestedServerNames()} 读到 SNI；DNS 钩子是 Mockito
 *       mock（只把 {@code api.example.invalid} 映射到 {@code 127.0.0.1}），而实际连接确实打到了只监听回环
 *       地址的本地服务 —— 所以"连接期用的是钩子返回的那个 IP"这次是<b>真实发生</b>的。</li>
 *   <li><b>证书主机名不匹配</b>：同一端点、证书只签 {@code api.example.invalid}，请求
 *       {@code wrong.example.invalid} 必须失败，且失败是固定码 {@code TRANSPORT_FAILED}（不带 host/port/原文）。
 *       该用例先做<b>同证书、同信任上下文的正向控制</b>（正确 host 必须成功），再要求服务端侧握手确实完成过
 *       （有界 {@code await}），所以“失败”不能被服务器/证书/信任库坏掉冒充。</li>
 *   <li><b>生产工厂拒绝自签证书</b>：走公开构造器（{@link RestrictedHttpClientFactory#INSTANCE}，JVM 默认
 *       信任库）时握手必须被拒 —— 本测试没有改默认信任库，也没有生成第二张证书；同样先做正向控制，排除
 *       “服务器根本没握手成功”这种假绿。</li>
 *   <li><b>不跟随重定向</b>：302 的 {@code Location} 指向同一服务器的另一个 path，客户端只发一次请求、
 *       只建一次连接，目标 path 从未被请求。</li>
 *   <li><b>超限断流</b>：chunked 真实 body 超过 512 KiB 时客户端报 {@code RESPONSE_TOO_LARGE}，并且服务端
 *       的写操作随后失败（客户端确实关闭了连接），写入量被上限与 5 秒窗口双重约束。</li>
 *   <li><b>不解压</b>：{@code Content-Encoding: gzip} 直接拒绝；另有一例证明正好 512 KiB 的 body 可以通过。</li>
 *   <li><b>不被 JVM 级 SOCKS 默认代理接管</b>：临时设置 {@code socksProxyHost}/{@code socksProxyPort}
 *       指向本地陷阱监听器，请求仍然成功、陷阱收到 0 个连接；系统属性在 {@code finally} 里全部恢复，且该用例
 *       用 {@link ResourceLock}（{@code SYSTEM_PROPERTIES}）声明了非并行。陷阱先进入 accept 循环（ready）再
 *       发请求，并且一直监听到 {@code close()}，不用“启动后固定时长”当观测窗口。</li>
 *   <li><b>close() 真的会关掉已 accept 的连接</b>：一条握手完成、但一个应用层字节都没发的连接上，服务端正
 *       阻塞在请求头读里；{@code close()} 必须关掉它并让接受线程在有界时间内结束（否则用例失败，不把
 *       {@code interrupt} 当 socket 取消）。</li>
 *   <li><b>媒体清单链路复用同一份执行器</b>：{@link RestrictedManifestHttpClient}（<b>独立</b>构造的媒体端点
 *       策略 + 同一份临时证书信任上下文 + 同一份 {@code RestrictedGetExecutor}）在真实 socket 上发一次
 *       {@code GET}，服务端逐字记录到含 {@code %2F}/{@code %7e}/{@code %2B}/{@code %2f}、重复参数与空值的
 *       raw path+query，{@code Host}/SNI 与固定 {@code Accept}/{@code Accept-Encoding: identity} 正确，
 *       并且 {@link HlsDurationProbe} 把 {@code 0.1 + 0.2} 精确算成 {@code 0.3}、两个分片；整次探测只有
 *       1 条连接 1 条请求（没有递归去取分片或密钥）。master 清单用例证明解析拒绝之后不再发第二次请求。</li>
 * </ul>
 *
 * <h2>它不验证什么（不要把它当成安全验收）</h2>
 * <ul>
 *   <li><b>不验证 DNS 分类器</b>：{@link ValidatedDnsResolver} 在这里被 mock 掉，只回答一个地址。地址分类
 *       （公网候选/私有段/保留名）由 {@code PublicAddressPolicyTests} 与 {@code ValidatedDnsResolverTests}
 *       独立覆盖；本类的 mock <b>刻意绕过</b>分类器，只为了在回环地址上验证 TLS 与传输行为。因此"分类器 + 真实
 *       连接"的组合不构成本切片对完整 SSRF / DNS rebinding 的验收。</li>
 *   <li>不验证媒体清单的<b>内容授权</b>与真实上游可达性：清单、密钥与分片地址都是 {@code example.invalid}
 *       合成值，解析通过只说明结构契约成立（由 {@code HlsMediaDurationParser} 决定）。</li>
 *   <li>不验证部署侧出口（透明代理/NAT/运营商）、不验证真实上游站点的证书链或稳定性。</li>
 *   <li>不验证 20 秒总预算的慢响应分支（那需要长等待），也不验证 Apache 组件自身的 wire debug 日志脱敏。</li>
 *   <li>主机名校验、SNI 与证书信任只覆盖 JVM 默认 TLS provider（本机 JDK）的实现行为。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "videoSourceLocalTls", matches = "true")
class RestrictedCmsHttpClientLocalTlsTests {

    private static final String BASE_PATH = "/api.php/provide/vod/";
    private static final String OTHER_HOST = "wrong.example.invalid";

    /** 媒体清单候选地址的 raw path：故意混用百分号编码大小写与编码后的斜杠，用来证明客户端逐字发出。 */
    private static final String MEDIA_RAW_PATH = "/signed/%2Fpart/%7e/index.m3u8";

    /** 媒体清单候选地址的 raw query：重复参数、空值、非字典序，以及 {@code %2B}/{@code %2f} 两种大小写。 */
    private static final String MEDIA_RAW_QUERY = "token=A%2BB%2fC&x=1&x=2&empty=";

    /** 媒体清单请求固定的 {@code Accept}（与 {@code RestrictedManifestHttpClient} 内部常量同值）。 */
    private static final String MEDIA_ACCEPT = "application/vnd.apple.mpegurl, application/x-mpegURL";

    /** 只有两个完整分片（0.1 + 0.2）的小型 ENDLIST 媒体清单；密钥与分片地址都是 {@code example.invalid} 合成值。 */
    private static final byte[] HLS_MEDIA_MANIFEST = ("#EXTM3U\n"
            + "#EXT-X-VERSION:3\n"
            + "#EXT-X-TARGETDURATION:1\n"
            + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://key.example.invalid/k.bin\"\n"
            + "#EXTINF:0.1,\n"
            + "https://segment.example.invalid/s1.ts\n"
            + "#EXTINF:0.2,\n"
            + "https://segment.example.invalid/s2.ts\n"
            + "#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);

    /** master 清单：解析器必须整体拒绝，且拒绝后不得再去取 variant 媒体清单。 */
    private static final byte[] HLS_MASTER_MANIFEST = ("#EXTM3U\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n"
            + "https://variant.example.invalid/v1/index.m3u8\n").getBytes(StandardCharsets.UTF_8);

    /** mock DNS 唯一会返回的地址：本机回环。 */
    private static final byte[] LOOPBACK_ADDRESS = {127, 0, 0, 1};

    private static final byte[] SMALL_BODY = "{\"code\":1,\"msg\":\"ok\"}".getBytes(StandardCharsets.UTF_8);

    /** 服务端最多再多写 1 MiB：超过 512 KiB 上限后客户端必然已 abort，服务端不能无限填充。 */
    private static final long EXTRA_OVERFLOW_BYTES = 1024L * 1024L;

    /** 超限用例的整体观察窗口：服务端写入与"客户端已关闭"的观测都必须在这段时间内结束。 */
    private static final long OVERFLOW_WINDOW_MILLIS = 5_000L;

    /** 所有“服务端观测”等待的上限（握手完成、连接数、陷阱 ready、关闭 join）：不超过 5 秒。 */
    private static final long AWAIT_MILLIS = 5_000L;

    private static final int OVERFLOW_CHUNK_BYTES = 16 * 1024;

    @TempDir
    static Path tempDirectory;

    private static LocalTlsTestCertificate certificate;

    @BeforeAll
    static void generateTemporaryCertificate() throws Exception {
        certificate = LocalTlsTestCertificate.generate(tempDirectory);
    }

    // ------------------------------------------------------------ 1. 正确证书 + 精确 host

    @Test
    void trustedCertificateWithTheExactHostDeliversTheFixedRequestAndBody() throws Exception {
        AtomicReference<LocalTlsTestServer.RecordedRequest> recorded = new AtomicReference<>();
        try (LocalTlsTestServer server = LocalTlsTestServer.start(certificate.newServerContext(), connection -> {
            recorded.set(connection.request());
            connection.sendFixed(200, "OK", List.of("Content-Type: application/json"), SMALL_BODY);
        })) {
            ValidatedDnsResolver dnsResolver = dnsResolverReturning(LocalTlsTestCertificate.HOST);
            RestrictedCmsHttpClient client = localTlsClient(LocalTlsTestCertificate.HOST, server.port(), dnsResolver);

            byte[] body = client.fetchList(2, 7);

            assertArrayEquals(SMALL_BODY, body, "响应体必须原样交回（不解压、不转码）");
            LocalTlsTestServer.RecordedRequest request = recorded.get();
            assertNotNull(request, "服务端必须真的收到这条请求");
            assertEquals("GET " + BASE_PATH + "?ac=list&pg=2&t=7 HTTP/1.1", request.requestLine(),
                    "path 与 query 必须由客户端按固定参数拼出，不重编码");
            assertEquals(LocalTlsTestCertificate.HOST + ":" + server.port(), request.hostHeader(),
                    "Host 头必须带上批准 base 的 host 与非 443 端口");
            assertEquals(LocalTlsTestCertificate.HOST, request.sniServerName(),
                    "服务端必须从 ExtendedSSLSession 读到 SNI=批准 base 的 host（读到 null 表示 JDK 侧没有发出 SNI，"
                            + "那是真实失败，不允许改成通过）");
            assertTrue(request.hasHeader("Accept", "application/json"));
            assertTrue(request.hasHeader("Accept-Encoding", "identity"),
                    "不接受压缩时客户端只声明 identity，不会自动加 gzip");
            verify(dnsResolver, atLeastOnce()).resolve(LocalTlsTestCertificate.HOST);
            assertEquals(1, server.acceptedConnections(), "一次请求只允许一次真实连接");
            assertEquals(1, server.requests().size());
        }
    }

    // ------------------------------------------------------------ 2. 证书主机名不匹配

    @Test
    void certificateIssuedForAnotherHostFailsHostnameVerificationWithTheFixedCode() throws Exception {
        try (LocalTlsTestServer server = LocalTlsTestServer.start(certificate.newServerContext(),
                connection -> connection.sendFixed(200, "OK", List.of(), SMALL_BODY))) {
            // 正向控制：同一台服务器、同一张证书、同一份“只信任这张证书”的上下文，先用证书签发的 host 打通一次。
            // 少了这一步，“错 host 必须失败”可能只是服务器/证书/信任上下文坏了，而不是主机名校验在起作用。
            byte[] controlBody = localTlsClient(LocalTlsTestCertificate.HOST, server.port(),
                    dnsResolverReturning(LocalTlsTestCertificate.HOST)).fetchList(1, null);
            assertArrayEquals(SMALL_BODY, controlBody, "正向控制失败：同一服务器的正确 host 必须成功");
            assertEquals(1, server.requests().size(), "正向控制必须真的到达应用层");
            assertTrue(server.awaitHandshakeCompletions(1, AWAIT_MILLIS),
                    "正向控制的服务端握手必须在有界时间内完成");

            // 同一个临时端点、同一张证书（只签 api.example.invalid），但 base 与 DNS 钩子都换成别的 host。
            RestrictedCmsHttpClient client = localTlsClient(OTHER_HOST, server.port(),
                    dnsResolverReturning(OTHER_HOST));

            CmsHttpRequestException failure =
                    assertThrows(CmsHttpRequestException.class, () -> client.fetchList(1, null));

            assertEquals(Reason.TRANSPORT_FAILED, failure.reason(),
                    "主机名校验失败必须折叠成固定码，不允许透出 TLS 原文");
            assertEquals(Reason.TRANSPORT_FAILED.description(), failure.getMessage());
            assertNull(failure.getCause(), "固定码异常不允许带 cause（原始 SSLException 会带主机名）");
            assertFalse(failure.getMessage().contains(OTHER_HOST));
            assertFalse(failure.getMessage().contains(Integer.toString(server.port())));

            // 关键区分：错 host 的这一次握手在服务端确实完成过（服务端侧计数有界 await 到 2），所以客户端拒绝的
            // 是证书/主机名校验，而不是“握手根本没跑起来”。这里不读 handshakeFailures 的瞬时值：TLS 1.3 下
            // 服务端可能先完成自己的握手再收到对端 alert，那个计数没有可靠的时序。
            assertTrue(server.awaitHandshakeCompletions(2, AWAIT_MILLIS),
                    "错 host 的握手必须在服务端完成过，否则这条用例只证明了“传输失败”（handshakeFailures="
                            + server.handshakeFailures() + "）");
            assertEquals(1, server.requests().size(),
                    "主机名校验失败时不允许发出应用层请求（只允许正向控制那一条）");
            assertEquals(LocalTlsTestCertificate.HOST + ":" + server.port(),
                    server.requests().get(0).hostHeader(), "到达应用层的只有正向控制那条请求");
            assertEquals(2, server.acceptedConnections(), "错 host 只允许建立一次真实连接（加上正向控制的一次）");
        }
    }

    // ------------------------------------------------------------ 3. 生产工厂拒绝临时自签证书

    @Test
    void productionFactoryRejectsTheTemporarySelfSignedCertificate() throws Exception {
        try (LocalTlsTestServer server = LocalTlsTestServer.start(certificate.newServerContext(),
                connection -> connection.sendFixed(200, "OK", List.of(), SMALL_BODY))) {
            // 正向控制：同一台服务器 + 同一张证书，用只信任这张证书的上下文必须成功。这样“生产工厂失败”才能
            // 归因于 JVM 默认信任库不认识这张自签证书，而不是服务器/证书根本没握手成功。
            byte[] controlBody = localTlsClient(LocalTlsTestCertificate.HOST, server.port(),
                    dnsResolverReturning(LocalTlsTestCertificate.HOST)).fetchList(1, null);
            assertArrayEquals(SMALL_BODY, controlBody,
                    "正向控制失败：说明服务器/证书本身坏了，本用例不再有证据力");

            // 公开构造器 = RestrictedHttpClientFactory.INSTANCE = SSLContexts.createDefault()（JVM 默认信任库）。
            // 本测试没有把临时证书导入默认信任库，所以这里必须失败。
            RestrictedCmsHttpClient client = new RestrictedCmsHttpClient(
                    baseUri(LocalTlsTestCertificate.HOST, server.port()),
                    policy(LocalTlsTestCertificate.HOST, server.port()),
                    dnsResolverReturning(LocalTlsTestCertificate.HOST));

            CmsHttpRequestException failure =
                    assertThrows(CmsHttpRequestException.class, () -> client.fetchList(1, null));

            assertEquals(Reason.TRANSPORT_FAILED, failure.reason());
            assertNull(failure.getCause());
            assertEquals(1, server.requests().size(), "生产信任链拒绝时必须没有第二次应用层请求");
            assertTrue(server.awaitAcceptedConnections(2, AWAIT_MILLIS),
                    "拒绝发生在握手阶段：第二次 TCP 连接必须已经建立（否则失败原因不明）");
        }
    }

    // ------------------------------------------------------------ 4. 不跟随重定向

    @Test
    void redirectResponseIsNotFollowedAndOnlyTheOriginalRequestIsSent() throws Exception {
        AtomicReference<String> redirectLocation = new AtomicReference<>();
        try (LocalTlsTestServer server = LocalTlsTestServer.start(certificate.newServerContext(),
                connection -> connection.sendFixed(302, "Found",
                        List.of("Location: " + redirectLocation.get()), new byte[0]))) {
            redirectLocation.set("https://" + LocalTlsTestCertificate.HOST + ":" + server.port() + "/redirected");
            RestrictedCmsHttpClient client = localTlsClient(LocalTlsTestCertificate.HOST, server.port(),
                    dnsResolverReturning(LocalTlsTestCertificate.HOST));

            CmsHttpRequestException failure =
                    assertThrows(CmsHttpRequestException.class, () -> client.fetchList(1, null));

            assertEquals(Reason.UPSTREAM_STATUS, failure.reason());
            assertEquals(302, failure.upstreamStatus(), "3xx 只保留状态整数，不跟随、不读 body");
            assertEquals(1, server.acceptedConnections(), "重定向目标不允许被访问：只能有一次真实连接");
            assertEquals(1, server.requests().size());
            assertEquals("GET " + BASE_PATH + "?ac=list&pg=1 HTTP/1.1", server.requests().get(0).requestLine());
            assertFalse(server.requests().get(0).requestLine().contains("/redirected"));
        }
    }

    // ------------------------------------------------------------ 5. 超限 + 服务端观测到断流

    @Test
    void chunkedBodyBeyondTheLimitIsRejectedAndTheServerObservesTheConnectionClosing() throws Exception {
        byte[] chunk = new byte[OVERFLOW_CHUNK_BYTES];
        AtomicLong writtenBytes = new AtomicLong();
        AtomicReference<LocalTlsTestServer> serverRef = new AtomicReference<>();
        CountDownLatch writeFailedAfterClientAbort = new CountDownLatch(1);
        long writeCap = (long) BoundedBodyReader.MAX_BODY_BYTES + EXTRA_OVERFLOW_BYTES;
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OVERFLOW_WINDOW_MILLIS);

        try (LocalTlsTestServer server = LocalTlsTestServer.start(certificate.newServerContext(), connection -> {
            connection.startChunkedResponse(List.of("Content-Type: application/json"));
            try {
                long remaining = writeCap;
                while (remaining > 0L && System.nanoTime() < deadlineNanos) {
                    // 每个 chunk 都按剩余额度收窄：服务端写入永远不会越过 writeCap（不靠放宽断言兜住）。
                    int chunkSize = (int) Math.min(chunk.length, remaining);
                    connection.writeChunk(chunkSize == chunk.length ? chunk : Arrays.copyOf(chunk, chunkSize));
                    writtenBytes.addAndGet(chunkSize);
                    remaining -= chunkSize;
                }
            } catch (IOException e) {
                // 只有服务端自己没在收尾（close()）时，这次写失败才是“客户端已断开”的观测。
                // 它证明的是：本次客户端报错之后服务端的写失败；不证明对端到底是 RST 还是 FIN。
                LocalTlsTestServer currentServer = serverRef.get();
                if (currentServer != null && !currentServer.isClosed()) {
                    writeFailedAfterClientAbort.countDown();
                }
                throw e;
            }
        })) {
            serverRef.set(server);
            RestrictedCmsHttpClient client = localTlsClient(LocalTlsTestCertificate.HOST, server.port(),
                    dnsResolverReturning(LocalTlsTestCertificate.HOST));

            CmsHttpRequestException failure =
                    assertThrows(CmsHttpRequestException.class, () -> client.fetchList(1, null));

            assertEquals(Reason.RESPONSE_TOO_LARGE, failure.reason());
            assertTrue(writeFailedAfterClientAbort.await(OVERFLOW_WINDOW_MILLIS, TimeUnit.MILLISECONDS),
                    "服务端必须观测到客户端关闭连接（写失败），不能只凭客户端自己报错就声称断流");
            assertTrue(writtenBytes.get() >= BoundedBodyReader.MAX_BODY_BYTES,
                    "服务端必须真的写满 512 KiB 上限（否则“超限”判定没被触发过），实际写入 "
                            + writtenBytes.get() + " 字节");
            assertTrue(writtenBytes.get() <= writeCap,
                    "服务端写入必须被上限约束（512 KiB + 1 MiB）：每个 chunk 按剩余额度收窄，实际写入 "
                            + writtenBytes.get() + " 字节");
        }
    }

    // ------------------------------------------------------------ 6. 不解压

    @Test
    void gzipContentEncodingIsRejectedWithoutDecompressingTheBody() throws Exception {
        byte[] gzipped = gzip(SMALL_BODY);
        try (LocalTlsTestServer server = LocalTlsTestServer.start(certificate.newServerContext(),
                connection -> connection.sendFixed(200, "OK",
                        List.of("Content-Encoding: gzip", "Content-Type: application/json"), gzipped))) {
            RestrictedCmsHttpClient client = localTlsClient(LocalTlsTestCertificate.HOST, server.port(),
                    dnsResolverReturning(LocalTlsTestCertificate.HOST));

            CmsHttpRequestException failure =
                    assertThrows(CmsHttpRequestException.class, () -> client.fetchList(1, null));

            assertEquals(Reason.CONTENT_ENCODING_NOT_IDENTITY, failure.reason());
            assertEquals(1, server.requests().size(), "拒绝发生在响应头阶段，请求本身已经发出过一次");
        }
    }

    // ------------------------------------------------------------ 7. 正好 512 KiB 通过

    @Test
    void bodyOfExactlyTheLimitIsAccepted() throws Exception {
        byte[] body = new byte[BoundedBodyReader.MAX_BODY_BYTES];
        Arrays.fill(body, (byte) 'a');
        try (LocalTlsTestServer server = LocalTlsTestServer.start(certificate.newServerContext(),
                connection -> connection.sendFixed(200, "OK", List.of("Content-Type: application/json"), body))) {
            RestrictedCmsHttpClient client = localTlsClient(LocalTlsTestCertificate.HOST, server.port(),
                    dnsResolverReturning(LocalTlsTestCertificate.HOST));

            byte[] received = client.fetchList(1, null);

            assertEquals(BoundedBodyReader.MAX_BODY_BYTES, received.length);
            assertArrayEquals(body, received);
        }
    }

    // ------------------------------------------------------------ 8. JVM 级 SOCKS 默认代理

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void jvmSocksDefaultProxyIsBypassedAndTheLocalTrapSeesNoConnection() throws Exception {
        try (LocalSocksTrapListener trap = LocalSocksTrapListener.start();
             LocalTlsTestServer server = LocalTlsTestServer.start(certificate.newServerContext(),
                     connection -> connection.sendFixed(200, "OK", List.of(), SMALL_BODY))) {

            // 明确 ready 再请求：陷阱必须先进入 accept 循环，否则“0 个连接”可能只是它还没开始监听。
            assertTrue(trap.awaitReady(AWAIT_MILLIS), "SOCKS 陷阱必须在发请求之前进入 accept 循环");

            String originalSocksHost = System.getProperty("socksProxyHost");
            String originalSocksPort = System.getProperty("socksProxyPort");
            byte[] body;
            try {
                // 把 JVM 级 SOCKS 默认指向本地陷阱：只有“落回默认代理”的原始 socket 才会连到这里。
                System.setProperty("socksProxyHost", "127.0.0.1");
                System.setProperty("socksProxyPort", Integer.toString(trap.port()));

                RestrictedCmsHttpClient client = localTlsClient(LocalTlsTestCertificate.HOST, server.port(),
                        dnsResolverReturning(LocalTlsTestCertificate.HOST));
                body = client.fetchList(1, null);
            } finally {
                restoreSystemProperty("socksProxyHost", originalSocksHost);
                restoreSystemProperty("socksProxyPort", originalSocksPort);
            }

            assertArrayEquals(SMALL_BODY, body, "设置了 SOCKS 默认代理后请求仍然必须成功");
            assertEquals(1, server.acceptedConnections());

            // 陷阱从 ready 起一直监听到 close()（每次 accept 的有界等待只回去复查 closed），观测窗口覆盖了整个
            // 请求：请求成功返回本身就说明没有走 SOCKS 代理 —— 陷阱不会回任何 SOCKS 应答，走代理的请求不可能成功。
            assertEquals(0, trap.acceptedConnections(),
                    "JVM 级 SOCKS 默认代理不允许接管受限客户端的原始 socket（Proxy.NO_PROXY）");
        }
    }

    // ------------------------------------------------------------ 9. close() 关掉已 accept 的连接

    @Test
    void closeClosesAnAcceptedConnectionThatIsStillWaitingForTheRequestHead() throws Exception {
        LocalTlsTestServer server = LocalTlsTestServer.start(certificate.newServerContext(),
                connection -> connection.sendFixed(200, "OK", List.of(), SMALL_BODY));
        try {
            // 一条真实 TLS 连接、握手完成，但一个应用层字节都不发：服务端此时阻塞在请求头读上
            // （5 秒读超时之前不会自己结束），只有 close() 真的关掉这条连接才能把它唤醒。
            try (SSLSocket clientSocket = (SSLSocket) certificate.newClientTrustContext().getSocketFactory()
                    .createSocket("127.0.0.1", server.port())) {
                clientSocket.startHandshake();
                assertTrue(server.awaitHandshakeCompletions(1, AWAIT_MILLIS),
                        "服务端必须先完成握手，才能证明后面被唤醒的是“已 accept 连接上的请求头读”");
                assertEquals(0, server.requests().size());

                server.close();

                assertTrue(server.acceptLoopFinished(), "close() 返回后接受线程必须已经结束");
                assertEquals(1, server.readsAbortedByClose(),
                        "close() 必须真正关掉已 accept 的连接：靠 5 秒读超时结束不会记这个数");
            }
        } finally {
            // 幂等收尾：正常路径上 close() 已经执行过，这里不会做任何事。
            server.close();
        }
    }

    // ------------------------------------------------------------ 10. 媒体清单：真实 GET + 逐字 raw path/query

    @Test
    void mediaManifestGetKeepsTheSignedRawPathAndQueryAndMakesExactlyOneRequest() throws Exception {
        AtomicReference<LocalTlsTestServer.RecordedRequest> recorded = new AtomicReference<>();
        try (LocalTlsTestServer server = LocalTlsTestServer.start(certificate.newServerContext(), connection -> {
            recorded.set(connection.request());
            connection.sendFixed(200, "OK",
                    List.of("Content-Type: application/vnd.apple.mpegurl"), HLS_MEDIA_MANIFEST);
        })) {
            ValidatedDnsResolver dnsResolver = dnsResolverReturning(LocalTlsTestCertificate.HOST);
            HlsDurationProbe probe = new HlsDurationProbe(localTlsManifestClient(server.port(), dnsResolver));

            HlsMediaDurationCandidate candidate = probe.probe(mediaUrl(server.port()));

            assertEquals(new BigDecimal("0.3"), candidate.durationSeconds(),
                    "0.1 + 0.2 必须精确求和为 0.3（BigDecimal 求和，不是二进制浮点）");
            assertEquals(2, candidate.segmentCount(), "两个完整分片都计入，密钥行不额外发请求");

            LocalTlsTestServer.RecordedRequest request = recorded.get();
            assertNotNull(request, "服务端必须真的收到这条媒体清单请求");
            assertEquals("GET " + MEDIA_RAW_PATH + "?" + MEDIA_RAW_QUERY + " HTTP/1.1", request.requestLine(),
                    "签名 raw path/query 必须逐字发出：百分号大小写、%2F、%2B、重复参数与空值都不许重编码或重排");
            assertEquals(LocalTlsTestCertificate.HOST + ":" + server.port(), request.hostHeader(),
                    "Host 头必须是媒体策略允许的 host 与实际随机端口");
            assertEquals(LocalTlsTestCertificate.HOST, request.sniServerName(),
                    "媒体链路必须发出与 Host 一致的 SNI（读到 null 就是真实失败，不允许改成通过）");
            assertTrue(request.hasHeader("Accept", MEDIA_ACCEPT), "媒体清单只声明两种 HLS 清单类型");
            assertTrue(request.hasHeader("Accept-Encoding", "identity"),
                    "不解压：媒体链路同样只声明 identity");
            assertNoCredentialHeaders(request);
            verify(dnsResolver, atLeastOnce()).resolve(LocalTlsTestCertificate.HOST);

            assertEquals(1, server.acceptedConnections(),
                    "一次 probe 只允许一次真实连接：不得递归去取分片或密钥");
            assertEquals(1, server.requests().size(), "一次 probe 只允许一条应用层请求");
        }
    }

    // ------------------------------------------------------------ 11. master 清单：拒绝且不再发请求

    @Test
    void masterManifestIsRejectedWithoutFollowingVariantPlaylists() throws Exception {
        try (LocalTlsTestServer server = LocalTlsTestServer.start(certificate.newServerContext(),
                connection -> connection.sendFixed(200, "OK",
                        List.of("Content-Type: application/vnd.apple.mpegurl"), HLS_MASTER_MANIFEST))) {
            HlsDurationProbe probe = new HlsDurationProbe(
                    localTlsManifestClient(server.port(), dnsResolverReturning(LocalTlsTestCertificate.HOST)));

            HlsManifestParseException failure = assertThrows(HlsManifestParseException.class,
                    () -> probe.probe(mediaUrl(server.port())));

            assertEquals(HlsManifestParseException.FailureCode.MASTER_PLAYLIST_UNSUPPORTED, failure.code(),
                    "master 清单必须整体拒绝：解析器不自动挑选媒体清单");
            assertEquals(1, server.acceptedConnections(), "拒绝之后不允许再去取 variant 清单");
            assertEquals(1, server.requests().size(), "唯一那条请求只能是原始候选地址");
            assertEquals("GET " + MEDIA_RAW_PATH + "?" + MEDIA_RAW_QUERY + " HTTP/1.1",
                    server.requests().get(0).requestLine(), "唯一那条请求必须逐字使用原始候选地址");
            assertFalse(server.requests().get(0).requestLine().contains("variant.example.invalid"));
        }
    }

    // ------------------------------------------------------------ helpers

    /** 媒体清单候选地址：同一个本机随机端口，签名 raw path/query 原样拼上（本类不改写、不重排）。 */
    private static String mediaUrl(int port) {
        return "https://" + LocalTlsTestCertificate.HOST + ":" + port + MEDIA_RAW_PATH + "?" + MEDIA_RAW_QUERY;
    }

    /**
     * 媒体清单客户端：策略是<b>独立构造</b>的（媒体端点允许列表不会从 CMS base 合并），TLS 信任材料与 CMS 链路
     * 用同一份临时证书上下文，执行器仍是同一份 {@code RestrictedGetExecutor}（不是复制的 HTTP 实现）。
     */
    private static RestrictedManifestHttpClient localTlsManifestClient(int port, ValidatedDnsResolver dnsResolver)
            throws GeneralSecurityException, IOException {
        return new RestrictedManifestHttpClient(policy(LocalTlsTestCertificate.HOST, port), dnsResolver,
                CmsRequestDeadline.SYSTEM_CLOCK,
                RestrictedHttpClientFactory.withSslContext(certificate.newClientTrustContext()));
    }

    /** 媒体清单请求不允许携带任何凭据头：服务端看到的原始头行里不能出现 Cookie/Authorization。 */
    private static void assertNoCredentialHeaders(LocalTlsTestServer.RecordedRequest request) {
        for (String line : request.headerLines()) {
            String lowerLine = line.toLowerCase();
            assertFalse(lowerLine.startsWith("cookie:"), "媒体清单请求不允许携带 Cookie");
            assertFalse(lowerLine.startsWith("authorization:"), "媒体清单请求不允许携带 Authorization");
        }
    }

    private static RestrictedCmsHttpClient localTlsClient(String host, int port, ValidatedDnsResolver dnsResolver)
            throws GeneralSecurityException, IOException {
        return new RestrictedCmsHttpClient(baseUri(host, port), policy(host, port), dnsResolver,
                CmsRequestDeadline.SYSTEM_CLOCK,
                RestrictedHttpClientFactory.withSslContext(certificate.newClientTrustContext()));
    }

    private static String baseUri(String host, int port) {
        return "https://" + host + ":" + port + BASE_PATH;
    }

    private static ExternalVideoUrlPolicy policy(String host, int port) {
        return new ExternalVideoUrlPolicy(
                Set.of(new ExternalVideoUrlPolicy.AllowedEndpoint(host, port)), 2_048);
    }

    /**
     * mock 掉 DNS 分类器：只把给定 host 映射到本机回环。
     *
     * <p>这是<b>刻意绕过</b>分类器（否则回环地址会被 {@link PublicAddressPolicy} 整批拒绝）。分类器本身由
     * {@code PublicAddressPolicyTests}/{@code ValidatedDnsResolverTests} 独立覆盖；本类只借用它前面的
     * host 语法检查（{@code api.example.invalid} 这类点分域名是合法的）。</p>
     */
    private static ValidatedDnsResolver dnsResolverReturning(String host) throws UnknownHostException {
        ValidatedDnsResolver dnsResolver = mock(ValidatedDnsResolver.class);
        when(dnsResolver.resolve(host)).thenReturn(new InetAddress[] {InetAddress.getByAddress(host, LOOPBACK_ADDRESS)});
        return dnsResolver;
    }

    private static void restoreSystemProperty(String name, String originalValue) {
        if (originalValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, originalValue);
        }
    }

    private static byte[] gzip(byte[] payload) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(collected)) {
            gzipOutputStream.write(payload);
        }
        return collected.toByteArray();
    }
}
