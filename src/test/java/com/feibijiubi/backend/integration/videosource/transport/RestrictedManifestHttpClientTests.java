package com.feibijiubi.backend.integration.videosource.transport;

import com.feibijiubi.backend.integration.videosource.ExternalVideoUrlPolicy;
import com.feibijiubi.backend.integration.videosource.ExternalVideoUrlPolicy.RejectionReason;
import com.feibijiubi.backend.integration.videosource.transport.CmsHttpRequestException.Reason;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.DnsResolver;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2-A：{@link RestrictedManifestHttpClient} 的<b>纯离线</b>单元测试 —— 没有真实 socket、没有真实 DNS、
 * 没有 Spring、没有数据库、不下载任何媒体。
 *
 * <h2>这些用例证明了什么</h2>
 * <ul>
 *   <li><b>接口形状</b>：只有一个入口 {@code fetchManifest(String)}；媒体端点允许列表由<b>单独传入</b>的策略
 *       决定（CMS 的 host 不会自动被合并），路径必须显式存在且以单个 {@code /} 开头，非 HTTPS/userinfo/片段
 *       一律拒绝，且所有拒绝都发生在创建客户端之前。</li>
 *   <li><b>签名地址逐字保留</b>：raw path 与 raw query（{@code %2F}、{@code %3B}、{@code +}、参数顺序）原样
 *       出现在请求行里；只有两个固定 header，没有 Cookie/Authorization 透传。</li>
 *   <li><b>响应契约与 CMS 客户端相同</b>：只接受 200（3xx 不跟随、非 200 不读 body）、非 {@code identity}
 *       的 Content-Encoding 拒绝、512 KiB 上界、空 body 拒绝、不看 Content-Type。这些行为与
 *       {@link RestrictedCmsHttpClient} 共用同一个 {@link RestrictedGetExecutor}，因此这里只抽查关键分支，
 *       {@link RestrictedCmsHttpClientTests} 保留原有完整矩阵，不重复复制。</li>
 *   <li><b>清理顺序</b>：没读到 EOF 时固定 {@code abort → client.close → body.close → response.close}；
 *       读到 EOF 时固定 {@code body.close → response.close → client.close}。</li>
 *   <li><b>脱敏与预算</b>：运行时失败折叠成 {@link Reason#TRANSPORT_FAILED}（无 cause、文案不带地址）；
 *       预算耗尽时不会返回部分 body，连接前就超预算时连客户端都不建。</li>
 * </ul>
 *
 * <h2>这些用例不证明什么</h2>
 * <p>不证明连接期真的用了校验后的地址（真实 socket/TLS 行为需联网环境，本切片没有做），不证明已 {@code abort}
 * 的请求真的被硬中断，也不证明清单内容合法 —— 后者由 {@code HlsMediaDurationParser} 决定。</p>
 */
class RestrictedManifestHttpClientTests {

    private static final String MEDIA_HOST = "cdn.example.com";

    /** 带签名 query 的候选地址：raw path 含百分号编码，query 含 {@code %2F}、{@code %3B}、{@code +} 与非字典序参数。 */
    private static final String SIGNED_URL = "https://cdn.example.com/media/2026/07/%E4%B8%AD%E6%96%87/list.m3u8"
            + "?auth_key=1712345678-0-0-abcdef0123456789&x-oss-process=style%2Fhls&a=%2Fb%3Bc+d";

    private static final String SIGNED_RAW_PATH = "/media/2026/07/%E4%B8%AD%E6%96%87/list.m3u8";

    private static final String SIGNED_RAW_QUERY =
            "auth_key=1712345678-0-0-abcdef0123456789&x-oss-process=style%2Fhls&a=%2Fb%3Bc+d";

    private static final byte[] PUBLIC_V4 = {1, 1, 1, 1};

    private static final long JUST_OVER_BUDGET_NANOS = RestrictedGetExecutor.TOTAL_CHECK_BUDGET_MILLIS * 1_000_000L + 1L;

    private final SteppingClock clock = new SteppingClock();
    private final List<DnsResolver> dnsResolversGivenToFactory = new ArrayList<>();
    private int factoryCalls;

    // ------------------------------------------------------------ 构造与地址策略

    @Test
    void rejectsMissingCollaborators() {
        ExternalVideoUrlPolicy mediaPolicy = mediaPolicy();
        ValidatedDnsResolver dnsResolver = dnsResolver();
        CmsHttpClientFactory clientFactory = factory(mock(CloseableHttpClient.class));

        assertThrows(IllegalArgumentException.class,
                () -> new RestrictedManifestHttpClient(null, dnsResolver, clock, clientFactory));
        assertThrows(IllegalArgumentException.class,
                () -> new RestrictedManifestHttpClient(mediaPolicy, null, clock, clientFactory));
        assertThrows(IllegalArgumentException.class,
                () -> new RestrictedManifestHttpClient(mediaPolicy, dnsResolver, null, clientFactory));
        assertThrows(IllegalArgumentException.class,
                () -> new RestrictedManifestHttpClient(mediaPolicy, dnsResolver, clock, null));
        // 生产构造器同样不接受 null 协作者，不会悄悄补一个默认实现。
        assertThrows(IllegalArgumentException.class, () -> new RestrictedManifestHttpClient(null, dnsResolver));
        assertThrows(IllegalArgumentException.class, () -> new RestrictedManifestHttpClient(mediaPolicy, null));
        assertEquals(0, factoryCalls);
    }

    @Test
    void rejectsAHostThatOnlyTheCmsPolicyAllowsWithoutCreatingAClient() {
        // 只允许 media host 的独立策略：CMS API 的 host 不会因为"同一个部署"就被自动合并进来。
        RestrictedManifestHttpClient client = clientWith(mock(CloseableHttpClient.class));

        ExternalVideoUrlPolicy.RejectedUrlException rejection = assertThrows(
                ExternalVideoUrlPolicy.RejectedUrlException.class,
                () -> client.fetchManifest("https://api.example.com/api.php/provide/vod/list.m3u8"));

        assertEquals(RejectionReason.ENDPOINT_NOT_ALLOWED, rejection.reason());
        assertEquals(0, factoryCalls);
    }

    @Test
    void rejectsCandidateUrlsWithoutAnExplicitSingleSlashPath() {
        RestrictedManifestHttpClient client = clientWith(mock(CloseableHttpClient.class));

        // host-only：本客户端不自动补写 '/'。
        assertThrows(IllegalArgumentException.class, () -> client.fetchManifest("https://" + MEDIA_HOST));
        // "//" 开头的 path：语义不唯一，同样拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> client.fetchManifest("https://" + MEDIA_HOST + "//media/list.m3u8"));
        assertEquals(0, factoryCalls);
    }

    @Test
    void rejectsNonHttpsUserInfoAndFragmentCandidates() {
        RestrictedManifestHttpClient client = clientWith(mock(CloseableHttpClient.class));

        assertEquals(RejectionReason.SCHEME_NOT_HTTPS, rejectionOf(client,
                "http://" + MEDIA_HOST + "/media/list.m3u8"));
        assertEquals(RejectionReason.USERINFO_PRESENT, rejectionOf(client,
                "https://user:pass@" + MEDIA_HOST + "/media/list.m3u8"));
        assertEquals(RejectionReason.FRAGMENT_PRESENT, rejectionOf(client,
                "https://" + MEDIA_HOST + "/media/list.m3u8#frag"));
        assertEquals(0, factoryCalls);
    }

    // ------------------------------------------------------------ 请求形状

    @Test
    void preservesTheSignedRawPathAndQueryAndSendsOnlyTheTwoFixedHeaders() throws IOException {
        CloseableHttpClient httpClient = mockClient(responseWithEntity(new ByteArrayEntity(new byte[] {1})));

        byte[] body = clientWith(httpClient).fetchManifest(SIGNED_URL);

        assertArrayEquals(new byte[] {1}, body);
        HttpUriRequest request = capturedRequest(httpClient);
        assertEquals("GET", request.getMethod());
        // 逐字保留：不重编码、不规范化、不重排 query。
        assertEquals(SIGNED_URL, request.getURI().toString());
        assertEquals(SIGNED_RAW_PATH, request.getURI().getRawPath());
        assertEquals(SIGNED_RAW_QUERY, request.getURI().getRawQuery());
        assertEquals(2, request.getAllHeaders().length);
        assertEquals("application/vnd.apple.mpegurl, application/x-mpegURL",
                request.getFirstHeader("Accept").getValue());
        assertEquals("identity", request.getFirstHeader("Accept-Encoding").getValue());
        assertNull(request.getFirstHeader("Cookie"));
        assertNull(request.getFirstHeader("Authorization"));
        assertNull(request.getFirstHeader("Range"));
        verify(httpClient, times(1)).execute(any(HttpUriRequest.class));
    }

    @Test
    void givesTheClientFactoryAValidatingResolverAroundTheInjectedLookup() throws Exception {
        CloseableHttpClient httpClient = mockClient(responseWithEntity(new ByteArrayEntity(new byte[] {1})));
        clientWith(httpClient).fetchManifest(SIGNED_URL);

        assertEquals(1, dnsResolversGivenToFactory.size());
        // 请求执行被 mock 替身接管，因此这里只证明"解析钩子已经接上"，不是真实解析证据。
        assertNotNull(dnsResolversGivenToFactory.get(0));
    }

    // ------------------------------------------------------------ 200 与 body

    @Test
    void returnsTheBodyForStatusTwoHundredRegardlessOfTheDeclaredContentType() throws IOException {
        byte[] manifest = "#EXTM3U\n".getBytes(StandardCharsets.UTF_8);
        CloseableHttpResponse response = responseWithEntity(new ByteArrayEntity(manifest));
        // 扩展名/Content-Type 都不是身份保证：这里故意声明 text/html，字节仍原样交回。
        when(response.getHeaders("Content-Type"))
                .thenReturn(new Header[] {new BasicHeader("Content-Type", "text/html;charset=utf-8")});
        CloseableHttpClient httpClient = mockClient(response);

        byte[] result = clientWith(httpClient).fetchManifest(SIGNED_URL);

        assertArrayEquals(manifest, result);
        InOrder closeOrder = inOrder(response, httpClient);
        closeOrder.verify(response).close();
        closeOrder.verify(httpClient).close();
    }

    @Test
    void acceptsExactlyTheMaximumBodySize() throws IOException {
        byte[] body = new byte[BoundedBodyReader.MAX_BODY_BYTES];
        CloseableHttpClient httpClient = mockClient(responseWithEntity(new ByteArrayEntity(body)));

        byte[] result = clientWith(httpClient).fetchManifest(SIGNED_URL);

        assertEquals(BoundedBodyReader.MAX_BODY_BYTES, result.length);
    }

    @Test
    void rejectsADeclaredLengthAboveTheMaximumBeforeReadingAnything() throws IOException {
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContentLength()).thenReturn((long) BoundedBodyReader.MAX_BODY_BYTES + 1);
        CloseableHttpClient httpClient = mockClient(responseWithEntity(entity));

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchManifest(SIGNED_URL));

        assertEquals(Reason.RESPONSE_TOO_LARGE, failure.reason());
        verify(entity, never()).getContent();
        verify(httpClient, times(1)).close();
    }

    @Test
    void treatsANullContentStreamAsAnEmptyBodyAndStillCleansUp() throws IOException {
        CloseLog log = new CloseLog();
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContentLength()).thenReturn(-1L);
        when(entity.getContent()).thenReturn(null);
        CloseableHttpClient httpClient = loggingClient(loggingResponse(200, entity, log), log);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchManifest(SIGNED_URL));

        assertEquals(Reason.EMPTY_BODY, failure.reason());
        assertEquals(CmsHttpRequestException.NO_UPSTREAM_STATUS, failure.upstreamStatus());
        assertTrue(log.requestAborted());
        assertEquals(List.of("client.close", "response.close"), log.events);
    }

    // ------------------------------------------------------------ 状态码与编码

    @Test
    void rejectsRedirectStatusesWithoutFollowingThem() throws IOException {
        CloseableHttpResponse response = response(302);
        CloseableHttpClient httpClient = mockClient(response);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchManifest(SIGNED_URL));

        assertEquals(Reason.UPSTREAM_STATUS, failure.reason());
        assertEquals(302, failure.upstreamStatus());
        // 没有下一跳：只发了一次请求，Location 与实体都没有被读取。
        verify(httpClient, times(1)).execute(any(HttpUriRequest.class));
        verify(response, never()).getFirstHeader("Location");
        verify(response, never()).getEntity();
        InOrder closeOrder = inOrder(httpClient, response);
        closeOrder.verify(httpClient).close();
        closeOrder.verify(response).close();
    }

    @Test
    void rejectsNonIdentityContentEncodingWithoutReadingTheBody() throws IOException {
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContentLength()).thenReturn(1L);
        CloseableHttpResponse response = responseWithEntity(entity);
        when(response.getHeaders("Content-Encoding"))
                .thenReturn(new Header[] {new BasicHeader("Content-Encoding", "gzip")});
        CloseableHttpClient httpClient = mockClient(response);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchManifest(SIGNED_URL));

        assertEquals(Reason.CONTENT_ENCODING_NOT_IDENTITY, failure.reason());
        verify(entity, never()).getContent();
        verify(httpClient, times(1)).close();
    }

    // ------------------------------------------------------------ 超限与清理顺序

    @Test
    void rejectsABodyOneByteOverTheLimitAndClosesInTheAbortFirstOrder() throws IOException {
        CloseLog log = new CloseLog();
        LoggingBodyStream body = LoggingBodyStream.endless(log);
        CloseableHttpClient httpClient =
                loggingClient(loggingResponse(200, entityReturning(body), log), log);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchManifest(SIGNED_URL));

        assertEquals(Reason.RESPONSE_TOO_LARGE, failure.reason());
        assertEquals(List.of("client.close", "body.close", "response.close"), log.events);
        assertTrue(log.abortedBeforeBodyClose, "body.close 时请求必须已经 abort");
        assertTrue(body.isClosed());
    }

    @Test
    void closesTheBodyResponseAndClientInOrderAfterAFullyReadBody() throws IOException {
        CloseLog log = new CloseLog();
        LoggingBodyStream body = LoggingBodyStream.of(log, new byte[] {7, 8});
        CloseableHttpClient httpClient =
                loggingClient(loggingResponse(200, entityReturning(body), log), log);

        assertArrayEquals(new byte[] {7, 8}, clientWith(httpClient).fetchManifest(SIGNED_URL));

        assertEquals(List.of("body.close", "response.close", "client.close"), log.events);
        assertFalse(log.requestAborted(), "读到 EOF 后不需要 abort 请求");
    }

    // ------------------------------------------------------------ 失败脱敏

    @Test
    void foldsRuntimeFailuresIntoTheFixedTransportCodeWithoutLeakingMessagesOrCauses() throws IOException {
        CloseableHttpClient executeFailure = mock(CloseableHttpClient.class);
        when(executeFailure.execute(any(HttpUriRequest.class))).thenThrow(new IllegalStateException(
                "connect failed to 10.1.2.3:443", new IOException(MEDIA_HOST)));

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(executeFailure).fetchManifest(SIGNED_URL));

        assertEquals(Reason.TRANSPORT_FAILED, failure.reason());
        assertEquals(Reason.TRANSPORT_FAILED.description(), failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("10.1.2.3"));
        assertFalse(failure.getMessage().contains(MEDIA_HOST));
        verify(executeFailure, times(1)).close();
    }

    // ------------------------------------------------------------ 协作式总预算

    @Test
    void failsBeforeCreatingAClientWhenTheBudgetIsAlreadyExhausted() throws IOException {
        clock.thenNanos(0L, JUST_OVER_BUDGET_NANOS);
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchManifest(SIGNED_URL));

        assertEquals(Reason.TOTAL_BUDGET_EXCEEDED, failure.reason());
        assertEquals(0, factoryCalls);
        verify(httpClient, never()).execute(any(HttpUriRequest.class));
    }

    @Test
    void doesNotReturnAPartiallyReadBodyWhenTheBudgetExpiresWhileReading() throws IOException {
        // 前四次读数在预算内：start、连接前、响应头后、读取前；读取后的检查超预算。
        clock.thenNanos(0L, 0L, 0L, 0L, JUST_OVER_BUDGET_NANOS);
        CloseableHttpClient httpClient = mockClient(responseWithEntity(new ByteArrayEntity(new byte[] {1, 2, 3})));

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchManifest(SIGNED_URL));

        assertEquals(Reason.TOTAL_BUDGET_EXCEEDED, failure.reason());
        verify(httpClient, times(1)).close();
    }

    // ------------------------------------------------------------ 辅助

    private static RejectionReason rejectionOf(RestrictedManifestHttpClient client, String candidateUrl) {
        return assertThrows(ExternalVideoUrlPolicy.RejectedUrlException.class,
                () -> client.fetchManifest(candidateUrl)).reason();
    }

    private RestrictedManifestHttpClient clientWith(CloseableHttpClient httpClient) {
        return new RestrictedManifestHttpClient(mediaPolicy(), dnsResolver(), clock, factory(httpClient));
    }

    private CmsHttpClientFactory factory(CloseableHttpClient httpClient) {
        return apacheDnsResolver -> {
            factoryCalls++;
            dnsResolversGivenToFactory.add(apacheDnsResolver);
            return httpClient;
        };
    }

    private ValidatedDnsResolver dnsResolver() {
        return new ValidatedDnsResolver(host -> new InetAddress[] {InetAddress.getByAddress(PUBLIC_V4)});
    }

    private static ExternalVideoUrlPolicy mediaPolicy() {
        return new ExternalVideoUrlPolicy(
                Set.of(new ExternalVideoUrlPolicy.AllowedEndpoint(
                        MEDIA_HOST, ExternalVideoUrlPolicy.DEFAULT_HTTPS_PORT)),
                2_048);
    }

    private static CloseableHttpClient mockClient(CloseableHttpResponse response) throws IOException {
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
        when(httpClient.execute(any(HttpUriRequest.class))).thenReturn(response);
        return httpClient;
    }

    private static HttpUriRequest capturedRequest(CloseableHttpClient httpClient) throws IOException {
        ArgumentCaptor<HttpUriRequest> captor = ArgumentCaptor.forClass(HttpUriRequest.class);
        verify(httpClient).execute(captor.capture());
        return captor.getValue();
    }

    private static CloseableHttpResponse response(int status) {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getStatusLine())
                .thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, status, "status"));
        when(response.getHeaders("Content-Encoding")).thenReturn(new Header[0]);
        return response;
    }

    private static CloseableHttpResponse responseWithEntity(HttpEntity entity) {
        CloseableHttpResponse response = response(200);
        when(response.getEntity()).thenReturn(entity);
        return response;
    }

    private static HttpEntity entityReturning(InputStream content) throws IOException {
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContentLength()).thenReturn(-1L);
        when(entity.getContent()).thenReturn(content);
        return entity;
    }

    private static CloseableHttpClient loggingClient(CloseableHttpResponse response, CloseLog log)
            throws IOException {
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
        when(httpClient.execute(any(HttpUriRequest.class))).thenAnswer(invocation -> {
            log.request = invocation.getArgument(0);
            return response;
        });
        doAnswer(invocation -> {
            log.events.add("client.close");
            return null;
        }).when(httpClient).close();
        return httpClient;
    }

    private static CloseableHttpResponse loggingResponse(int status, HttpEntity entity, CloseLog log)
            throws IOException {
        CloseableHttpResponse response = response(status);
        if (entity != null) {
            when(response.getEntity()).thenReturn(entity);
        }
        doAnswer(invocation -> {
            log.events.add("response.close");
            return null;
        }).when(response).close();
        return response;
    }

    /**
     * 关闭顺序记录器：client / response / body 三个替身把"我关了"按真实发生顺序记进同一个列表，
     * body 关闭时还记录当时请求是否已经 abort。
     */
    private static final class CloseLog {

        private final List<String> events = new ArrayList<>();
        private HttpUriRequest request;
        private boolean abortedBeforeBodyClose;

        boolean requestAborted() {
            return request != null && request.isAborted();
        }
    }

    /**
     * 有状态的响应体流替身：close() 记录自己的关闭时点与当时的 abort 状态。
     *
     * <p>它不是 Apache 的 {@code EofSensorInputStream}，所以不复制真实的"排空剩余 body"行为；它证明的是
     * <b>顺序</b>：真实实现里 body 流与响应的 close() 都会去排空，本类保证它们排在 abort 与 client.close 之后。</p>
     */
    private static final class LoggingBodyStream extends InputStream {

        private final CloseLog log;
        private final byte[] payload;
        private final boolean endless;
        private int position;
        private boolean closed;

        private LoggingBodyStream(CloseLog log, byte[] payload, boolean endless) {
            this.log = log;
            this.payload = payload;
            this.endless = endless;
        }

        static LoggingBodyStream of(CloseLog log, byte[] payload) {
            return new LoggingBodyStream(log, payload, false);
        }

        static LoggingBodyStream endless(CloseLog log) {
            return new LoggingBodyStream(log, null, true);
        }

        boolean isClosed() {
            return closed;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (closed) {
                throw new IOException("stream already closed");
            }
            if (endless) {
                Arrays.fill(buffer, offset, offset + length, (byte) 0);
                return length;
            }
            if (payload == null || position >= payload.length) {
                return -1;
            }
            int copied = Math.min(length, payload.length - position);
            System.arraycopy(payload, position, buffer, offset, copied);
            position += copied;
            return copied;
        }

        @Override
        public void close() {
            closed = true;
            log.abortedBeforeBodyClose = log.requestAborted();
            log.events.add("body.close");
        }
    }

    /**
     * 受控逻辑时钟：按给定读数依次返回，用完后一直返回最后一个读数。
     *
     * <p>这样"预算刚好用尽"与"读取中超出预算"都能离线复现，不需要真的 sleep。</p>
     */
    private static final class SteppingClock implements CmsRequestDeadline.Clock {

        private final Deque<Long> readings = new ArrayDeque<>();
        private long lastReading;

        SteppingClock thenNanos(long... nanos) {
            for (long nano : nanos) {
                readings.add(nano);
            }
            return this;
        }

        @Override
        public long nanoTime() {
            Long next = readings.poll();
            if (next != null) {
                lastReading = next;
            }
            return lastReading;
        }
    }
}
