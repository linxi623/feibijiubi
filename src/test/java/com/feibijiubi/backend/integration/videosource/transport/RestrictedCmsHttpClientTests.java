package com.feibijiubi.backend.integration.videosource.transport;

import com.feibijiubi.backend.integration.videosource.ExternalVideoUrlPolicy;
import com.feibijiubi.backend.integration.videosource.transport.CmsHttpRequestException.Reason;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.DnsResolver;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicStatusLine;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
 * E2-C2：{@link RestrictedCmsHttpClient} 的<b>纯离线</b>单元测试 —— 没有真实 socket、没有真实 DNS、
 * 没有 Spring、没有数据库。
 *
 * <h2>这些用例证明了什么</h2>
 * <ul>
 *   <li>接口形状：只有目录/单作品两个入口，host/path 来自批准 base，query 由固定参数拼成，header 只有
 *       {@code Accept}/{@code Accept-Encoding}，非法参数在创建任何客户端之前就被拒绝。base 必须<b>显式带
 *       路径</b>（root {@code /} 合法）：host-only 与 {@code //} 开头的 base 在构造期拒绝且不创建客户端。</li>
 *   <li>响应契约：只接受 200、不跟随 3xx、非 200 不读 body、非 {@code identity} 的 Content-Encoding 拒绝、
 *       512 KiB 上界（含 Content-Length 提前拒绝、未知长度按实际字节判定、正好 512 KiB 通过）、空 body 拒绝、
 *       不看 Content-Type。</li>
 *   <li>失败脱敏与清理：IO/TLS 失败与其它的运行时异常都折叠成固定原因码，已经确定的固定码
 *       （包括工厂报的 {@code CLIENT_INIT_FAILED}）原样上抛；两者都不带原始文案/cause。
 *       {@code getContent()} 返回 {@code null} 与“没有实体”一样算空 body。每条分支都关闭响应与客户端。</li>
 *   <li><b>关闭顺序</b>：只要 body 没读到 EOF（提前拒绝、超限、超预算、读取 IO 失败、拿不到流、关闭本身
 *       抛异常），顺序固定为 {@code abort → client.close → body.close → response.close}；读完 EOF 时固定为
 *       {@code body.close → response.close → client.close}。这些用例断言的是<b>顺序</b>，不是"各自发生过"。</li>
 *   <li>协作式预算：受控逻辑时钟下，"连接前已超预算"会在建客户端之前失败，"读取中超预算"不会返回部分
 *       body；刚好等于预算仍然放行。</li>
 * </ul>
 *
 * <h2>这些用例不证明什么（别把绿当作网络安全验收）</h2>
 * <ul>
 *   <li>不证明连接期真的用了校验后的地址：真实 socket/TLS/SNI 行为需要联网环境，
 *       {@link RestrictedHttpClientFactoryTests} 只钉住“解析钩子被交给连接管理器”与“原始 socket 显式
 *       {@code Proxy.NO_PROXY}”这两个结构事实。</li>
 *   <li>"已经 abort"是用 {@code HttpUriRequest#isAborted()}（{@code abort()} 置位的标记位）观测的，
 *       它只证明<b>本类调用了 abort</b>，不证明底层 socket 真的被硬中断、也不证明一个已经阻塞的 DNS 查询
 *       或 {@code read} 被打断：这些仍然只能靠 socket 超时（见 {@link CmsRequestDeadline} 的承诺边界）。
 *       body/response 的替身是普通流与 mock，不是 Apache 的 {@code EofSensorInputStream}，
 *       所以这里证明的是"顺序不会让排空先发生"，而不是复现过真实的无限 body 排空。</li>
 *   <li>不证明超时数值在真实网络上生效，也不证明外部 Apache 组件的 wire debug 日志被脱敏。</li>
 * </ul>
 */
class RestrictedCmsHttpClientTests {

    private static final String BASE_URI = "https://api.example.com/api.php/provide/vod/";
    private static final String BASE_PATH = "/api.php/provide/vod/";
    private static final byte[] PUBLIC_V4 = {1, 1, 1, 1};
    private static final long JUST_OVER_BUDGET_NANOS = RestrictedGetExecutor.TOTAL_CHECK_BUDGET_MILLIS * 1_000_000L + 1L;

    private final SteppingClock clock = new SteppingClock();
    private final List<String> lookedUpHosts = new ArrayList<>();
    private final List<DnsResolver> dnsResolversGivenToFactory = new ArrayList<>();
    private int factoryCalls;

    // ------------------------------------------------------------ 构造与参数校验

    @Test
    void rejectsBaseUriThatIsNotAnApprovedEndpoint() {
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);

        assertThrows(IllegalArgumentException.class, () -> new RestrictedCmsHttpClient(
                "https://evil.example.net/api.php/provide/vod/", policy(), dnsResolver(), clock,
                factory(httpClient)));
        assertEquals(0, factoryCalls);
    }

    @Test
    void rejectsBaseUriThatCarriesItsOwnQuery() {
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);

        assertThrows(IllegalArgumentException.class, () -> new RestrictedCmsHttpClient(
                "https://api.example.com/api.php/provide/vod/?ac=list", policy(), dnsResolver(), clock,
                factory(httpClient)));
        assertEquals(0, factoryCalls);
    }

    @Test
    void rejectsABaseUriWithoutAnExplicitPathOrWithADoubleSlashPrefix() {
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
        CmsHttpClientFactory countingFactory = factory(httpClient);

        // host-only：本组件不自动补写 '/'，所以在构造期就失败（而不是悄悄把空 path 当 '/' 用）。
        assertThrows(IllegalArgumentException.class, () -> new RestrictedCmsHttpClient(
                "https://api.example.com", policy(), dnsResolver(), clock, countingFactory));
        // "//" 开头的 path：语义不唯一，同样在构造期拒绝。
        assertThrows(IllegalArgumentException.class, () -> new RestrictedCmsHttpClient(
                "https://api.example.com//api.php/provide/vod/", policy(), dnsResolver(), clock,
                countingFactory));
        assertEquals(0, factoryCalls);
    }

    @Test
    void acceptsARootPathBaseAndAppendsTheFixedQuery() throws IOException {
        CloseableHttpClient httpClient = mockClient(responseWithEntity(new ByteArrayEntity(new byte[] {1})));
        RestrictedCmsHttpClient cms = new RestrictedCmsHttpClient("https://api.example.com/", policy(),
                dnsResolver(), clock, factory(httpClient));

        cms.fetchList(3, null);

        HttpUriRequest request = capturedRequest(httpClient);
        assertEquals("https://api.example.com/?ac=list&pg=3", request.getURI().toString());
        assertEquals("/", request.getURI().getRawPath());
    }

    @Test
    void rejectsMissingCollaborators() {
        ExternalVideoUrlPolicy urlPolicy = policy();
        ValidatedDnsResolver dnsResolver = dnsResolver();
        CmsHttpClientFactory clientFactory = factory(mock(CloseableHttpClient.class));

        assertThrows(IllegalArgumentException.class,
                () -> new RestrictedCmsHttpClient(BASE_URI, null, dnsResolver, clock, clientFactory));
        assertThrows(IllegalArgumentException.class,
                () -> new RestrictedCmsHttpClient(BASE_URI, urlPolicy, null, clock, clientFactory));
        assertThrows(IllegalArgumentException.class,
                () -> new RestrictedCmsHttpClient(BASE_URI, urlPolicy, dnsResolver, null, clientFactory));
        assertThrows(IllegalArgumentException.class,
                () -> new RestrictedCmsHttpClient(BASE_URI, urlPolicy, dnsResolver, clock, null));
        assertEquals(0, factoryCalls);
    }

    @Test
    void rejectsOutOfRangeListArgumentsWithoutCreatingAClient() {
        RestrictedCmsHttpClient cms = clientWith(mock(CloseableHttpClient.class));

        assertThrows(IllegalArgumentException.class, () -> cms.fetchList(0, null));
        assertThrows(IllegalArgumentException.class,
                () -> cms.fetchList(RestrictedCmsHttpClient.MAX_LIST_PAGE + 1, null));
        assertThrows(IllegalArgumentException.class, () -> cms.fetchList(1, -1));
        assertEquals(0, factoryCalls);
    }

    @Test
    void rejectsNonCanonicalWorkIdsWithoutCreatingAClient() {
        RestrictedCmsHttpClient cms = clientWith(mock(CloseableHttpClient.class));
        String tooLong = "1".repeat(RestrictedCmsHttpClient.MAX_WORK_ID_LENGTH + 1);

        assertThrows(IllegalArgumentException.class, () -> cms.fetchDetail(null));
        assertThrows(IllegalArgumentException.class, () -> cms.fetchDetail(""));
        assertThrows(IllegalArgumentException.class, () -> cms.fetchDetail("0"));
        assertThrows(IllegalArgumentException.class, () -> cms.fetchDetail("007"));
        assertThrows(IllegalArgumentException.class, () -> cms.fetchDetail("22248a"));
        assertThrows(IllegalArgumentException.class, () -> cms.fetchDetail("-1"));
        assertThrows(IllegalArgumentException.class, () -> cms.fetchDetail(" 222486"));
        assertThrows(IllegalArgumentException.class, () -> cms.fetchDetail(tooLong));
        assertEquals(0, factoryCalls);
    }

    // ------------------------------------------------------------ 请求形状

    @Test
    void sendsListRequestWithFixedQueryAndOnlyTheTwoFixedHeaders() throws IOException {
        CloseableHttpClient httpClient = mockClient(responseWithEntity(new ByteArrayEntity(new byte[] {1})));
        RestrictedCmsHttpClient cms = clientWith(httpClient);

        cms.fetchList(2, 7);

        HttpUriRequest request = capturedRequest(httpClient);
        assertEquals("GET", request.getMethod());
        assertEquals(BASE_URI + "?ac=list&pg=2&t=7", request.getURI().toString());
        assertEquals(BASE_PATH, request.getURI().getRawPath());
        assertEquals("ac=list&pg=2&t=7", request.getURI().getRawQuery());
        assertEquals("https://api.example.com", request.getURI().getScheme() + "://" + request.getURI().getHost());
        assertEquals(2, request.getAllHeaders().length);
        assertEquals("application/json", request.getFirstHeader("Accept").getValue());
        assertEquals("identity", request.getFirstHeader("Accept-Encoding").getValue());
        assertNull(request.getFirstHeader("Cookie"));
        assertNull(request.getFirstHeader("Authorization"));
        assertNull(request.getFirstHeader("Content-Type"));
    }

    @Test
    void sendsDetailRequestWithFixedQueryAndOmitsCategoryWhenAbsent() throws IOException {
        CloseableHttpClient httpClient = mockClient(responseWithEntity(new ByteArrayEntity(new byte[] {1})));
        RestrictedCmsHttpClient cms = clientWith(httpClient);

        cms.fetchDetail("222486");

        assertEquals(BASE_URI + "?ac=detail&ids=222486", capturedRequest(httpClient).getURI().toString());

        CloseableHttpClient second = mockClient(responseWithEntity(new ByteArrayEntity(new byte[] {1})));
        clientWith(second).fetchList(1, null);
        assertEquals(BASE_URI + "?ac=list&pg=1", capturedRequest(second).getURI().toString());
    }

    @Test
    void givesTheClientFactoryAValidatingResolverAroundTheInjectedLookup() throws Exception {
        CloseableHttpClient httpClient = mockClient(responseWithEntity(new ByteArrayEntity(new byte[] {1})));
        clientWith(httpClient).fetchList(1, null);

        assertEquals(1, dnsResolversGivenToFactory.size());
        DnsResolver apacheDnsResolver = dnsResolversGivenToFactory.get(0);
        assertTrue(lookedUpHosts.isEmpty(), "请求执行被 mock 替身接管，测试期间不应发生真实解析");

        InetAddress[] resolved = apacheDnsResolver.resolve("cdn.example.com");
        assertEquals(1, resolved.length);
        assertEquals(List.of("cdn.example.com"), lookedUpHosts);

        UnknownHostException rejected =
                assertThrows(UnknownHostException.class, () -> apacheDnsResolver.resolve("localhost"));
        assertNull(rejected.getMessage());
        assertNull(rejected.getCause());
    }

    // ------------------------------------------------------------ 200 与 body

    @Test
    void returnsTheBodyForStatusTwoHundredRegardlessOfTheDeclaredContentType() throws IOException {
        byte[] body = "{\"code\":1}".getBytes(StandardCharsets.UTF_8);
        CloseableHttpResponse response = responseWithEntity(new ByteArrayEntity(body));
        // 上游实测就是 text/html 里装 JSON：本客户端不看 Content-Type，只把字节原样交出去。
        when(response.getHeaders("Content-Type"))
                .thenReturn(new Header[] {new BasicHeader("Content-Type", "text/html;charset=utf-8")});
        CloseableHttpClient httpClient = mockClient(response);

        byte[] result = clientWith(httpClient).fetchList(1, null);

        assertArrayEquals(body, result);
        verify(httpClient, times(1)).execute(any(HttpUriRequest.class));
        InOrder closeOrder = inOrder(response, httpClient);
        closeOrder.verify(response).close();
        closeOrder.verify(httpClient).close();
    }

    @Test
    void acceptsExactlyTheMaximumBodySize() throws IOException {
        byte[] body = new byte[BoundedBodyReader.MAX_BODY_BYTES];
        CloseableHttpClient httpClient = mockClient(responseWithEntity(new ByteArrayEntity(body)));

        byte[] result = clientWith(httpClient).fetchList(1, null);

        assertEquals(BoundedBodyReader.MAX_BODY_BYTES, result.length);
    }

    @Test
    void rejectsOneByteMoreThanTheMaximumForAnUnknownLengthBody() throws IOException {
        CloseableHttpResponse response = responseWithEntity(
                new InputStreamEntity(new ByteArrayInputStream(new byte[BoundedBodyReader.MAX_BODY_BYTES + 1])));
        CloseableHttpClient httpClient = mockClient(response);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertEquals(Reason.RESPONSE_TOO_LARGE, failure.reason());
        assertEquals(CmsHttpRequestException.NO_UPSTREAM_STATUS, failure.upstreamStatus());
        verify(response, times(1)).close();
        verify(httpClient, times(1)).close();
    }

    @Test
    void rejectsADeclaredLengthAboveTheMaximumBeforeReadingAnything() throws IOException {
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContentLength()).thenReturn((long) BoundedBodyReader.MAX_BODY_BYTES + 1);
        CloseableHttpResponse response = responseWithEntity(entity);
        CloseableHttpClient httpClient = mockClient(response);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertEquals(Reason.RESPONSE_TOO_LARGE, failure.reason());
        verify(entity, never()).getContent();
        verify(response, times(1)).close();
        verify(httpClient, times(1)).close();
    }

    @Test
    void rejectsMissingAndEmptyBodies() throws IOException {
        CloseableHttpResponse withoutEntity = response(200);
        CmsHttpRequestException missing = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(mockClient(withoutEntity)).fetchList(1, null));
        assertEquals(Reason.EMPTY_BODY, missing.reason());

        CloseableHttpResponse withEmptyEntity = responseWithEntity(new ByteArrayEntity(new byte[0]));
        CmsHttpRequestException empty = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(mockClient(withEmptyEntity)).fetchList(1, null));
        assertEquals(Reason.EMPTY_BODY, empty.reason());
    }

    @Test
    void treatsANullContentStreamAsAnEmptyBodyAndStillCleansUp() throws IOException {
        CloseOrderRecorder recorder = new CloseOrderRecorder();
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContentLength()).thenReturn(-1L);
        when(entity.getContent()).thenReturn(null);
        CloseableHttpClient httpClient =
                recordingClient(recordingResponse(200, entity, recorder), recorder);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertEquals(Reason.EMPTY_BODY, failure.reason());
        assertEquals(CmsHttpRequestException.NO_UPSTREAM_STATUS, failure.upstreamStatus());
        assertTrue(recorder.requestAborted());
        assertEquals(List.of("client.close", "response.close"), recorder.events());
    }

    // ------------------------------------------------------------ 状态码

    @Test
    void rejectsRedirectStatusesWithoutFollowingThem() throws IOException {
        CloseableHttpResponse response = response(302);
        CloseableHttpClient httpClient = mockClient(response);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertEquals(Reason.UPSTREAM_STATUS, failure.reason());
        assertEquals(302, failure.upstreamStatus());
        // 没有下一跳：只有一个请求被发出，Location 头也从未被读取。
        verify(httpClient, times(1)).execute(any(HttpUriRequest.class));
        verify(response, never()).getFirstHeader("Location");
        verify(response, never()).getEntity();
        verify(httpClient, times(1)).close();
        InOrder closeOrder = inOrder(httpClient, response);
        closeOrder.verify(httpClient).close();
        closeOrder.verify(response).close();
    }

    @Test
    void rejectsNonOkStatusesWithoutReadingTheBody() throws IOException {
        for (int status : new int[] {429, 500}) {
            HttpEntity entity = mock(HttpEntity.class);
            when(entity.getContentLength()).thenReturn(16L);
            CloseableHttpResponse response = response(status);
            when(response.getEntity()).thenReturn(entity);
            CloseableHttpClient httpClient = mockClient(response);

            CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                    () -> clientWith(httpClient).fetchList(1, null));

            assertEquals(Reason.UPSTREAM_STATUS, failure.reason());
            assertEquals(status, failure.upstreamStatus());
            verify(entity, never()).getContent();
            verify(httpClient, times(1)).close();
            verify(response, times(1)).close();
        }
    }

    // ------------------------------------------------------------ Content-Encoding

    @Test
    void rejectsAnyNonIdentityContentEncodingIncludingMultiValueForms() throws IOException {
        Header[][] rejectedEncodings = {
                {new BasicHeader("Content-Encoding", "gzip")},
                {new BasicHeader("Content-Encoding", "identity"), new BasicHeader("Content-Encoding", "gzip")},
                {new BasicHeader("Content-Encoding", "gzip, identity")},
                {new BasicHeader("Content-Encoding", "")},
        };

        for (Header[] encodings : rejectedEncodings) {
            HttpEntity entity = mock(HttpEntity.class);
            when(entity.getContentLength()).thenReturn(1L);
            CloseableHttpResponse response = responseWithEntity(entity);
            when(response.getHeaders("Content-Encoding")).thenReturn(encodings);
            CloseableHttpClient httpClient = mockClient(response);

            CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                    () -> clientWith(httpClient).fetchList(1, null));

            assertEquals(Reason.CONTENT_ENCODING_NOT_IDENTITY, failure.reason());
            verify(entity, never()).getContent();
            verify(httpClient, times(1)).close();
        }
    }

    @Test
    void acceptsAnExplicitIdentityContentEncoding() throws IOException {
        byte[] body = {9, 9};
        CloseableHttpResponse response = responseWithEntity(new ByteArrayEntity(body));
        when(response.getHeaders("Content-Encoding"))
                .thenReturn(new Header[] {new BasicHeader("Content-Encoding", "identity")});

        assertArrayEquals(body, clientWith(mockClient(response)).fetchList(1, null));
    }

    // ------------------------------------------------------------ 失败脱敏

    @Test
    void foldsIoAndTlsFailuresIntoTheFixedTransportCodeWithoutLeakingTheCause() throws IOException {
        CloseableHttpClient ioFailure = mock(CloseableHttpClient.class);
        when(ioFailure.execute(any(HttpUriRequest.class)))
                .thenThrow(new IOException("connect failed to 10.1.2.3:443"));
        CmsHttpRequestException io = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(ioFailure).fetchList(1, null));
        assertEquals(Reason.TRANSPORT_FAILED, io.reason());
        assertNull(io.getCause());
        assertFalse(io.getMessage().contains("10.1.2.3"));
        assertEquals(CmsHttpRequestException.NO_UPSTREAM_STATUS, io.upstreamStatus());
        verify(ioFailure, times(1)).close();

        CloseableHttpClient tlsFailure = mock(CloseableHttpClient.class);
        when(tlsFailure.execute(any(HttpUriRequest.class)))
                .thenThrow(new SSLHandshakeException("PKIX path building failed: api.example.com"));
        CmsHttpRequestException tls = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(tlsFailure).fetchDetail("222486"));
        assertEquals(Reason.TRANSPORT_FAILED, tls.reason());
        assertNull(tls.getCause());
        assertFalse(tls.getMessage().contains("api.example.com"));
        verify(tlsFailure, times(1)).close();
    }

    @Test
    void foldsRuntimeFailuresIntoTheFixedTransportCodeWithoutLeakingMessagesOrCauses() throws IOException {
        // execute 抛运行时异常：文案里带地址，cause 里带主机名，两者都不能透出。
        CloseableHttpClient executeFailure = mock(CloseableHttpClient.class);
        when(executeFailure.execute(any(HttpUriRequest.class))).thenThrow(new IllegalStateException(
                "connect failed to 10.1.2.3:443", new IOException("api.example.com")));
        CmsHttpRequestException fromExecute = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(executeFailure).fetchList(1, null));
        assertEquals(Reason.TRANSPORT_FAILED, fromExecute.reason());
        assertEquals(Reason.TRANSPORT_FAILED.description(), fromExecute.getMessage());
        assertNull(fromExecute.getCause());
        assertFalse(fromExecute.getMessage().contains("10.1.2.3"));
        verify(executeFailure, times(1)).close();

        // getContent 抛运行时异常（嵌套 cause 同样不能透出），清理顺序与 IO 失败一致。
        CloseOrderRecorder recorder = new CloseOrderRecorder();
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContentLength()).thenReturn(-1L);
        when(entity.getContent()).thenThrow(new IllegalStateException(
                "no stream for 10.1.2.3:443", new IOException("PKIX api.example.com")));
        CloseableHttpClient streamFailure =
                recordingClient(recordingResponse(200, entity, recorder), recorder);
        CmsHttpRequestException fromStream = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(streamFailure).fetchList(1, null));
        assertEquals(Reason.TRANSPORT_FAILED, fromStream.reason());
        assertNull(fromStream.getCause());
        assertFalse(fromStream.getMessage().contains("api.example.com"));
        assertTrue(recorder.requestAborted());
        assertEquals(List.of("client.close", "response.close"), recorder.events());
    }

    @Test
    void foldsFactoryRuntimeFailuresButKeepsAFixedCodeReportedByTheFactory() {
        CmsHttpClientFactory runtimeFactory = apacheDnsResolver -> {
            throw new IllegalStateException("keystore /etc/secret.jks unreadable");
        };
        RestrictedCmsHttpClient runtimeClient =
                new RestrictedCmsHttpClient(BASE_URI, policy(), dnsResolver(), clock, runtimeFactory);
        CmsHttpRequestException runtimeFailure = assertThrows(CmsHttpRequestException.class,
                () -> runtimeClient.fetchList(1, null));
        assertEquals(Reason.TRANSPORT_FAILED, runtimeFailure.reason());
        assertNull(runtimeFailure.getCause());
        assertFalse(runtimeFailure.getMessage().contains("keystore"));

        // 工厂已经给出的固定码（本地 SSL 初始化失败）不能被折叠成 TRANSPORT_FAILED。
        CmsHttpClientFactory fixedCodeFactory = apacheDnsResolver -> {
            throw new CmsHttpRequestException(Reason.CLIENT_INIT_FAILED);
        };
        RestrictedCmsHttpClient fixedCodeClient =
                new RestrictedCmsHttpClient(BASE_URI, policy(), dnsResolver(), clock, fixedCodeFactory);
        CmsHttpRequestException fixedCodeFailure = assertThrows(CmsHttpRequestException.class,
                () -> fixedCodeClient.fetchList(1, null));
        assertEquals(Reason.CLIENT_INIT_FAILED, fixedCodeFailure.reason());
        assertEquals(Reason.CLIENT_INIT_FAILED.description(), fixedCodeFailure.getMessage());
        assertNull(fixedCodeFailure.getCause());
        assertEquals(CmsHttpRequestException.NO_UPSTREAM_STATUS, fixedCodeFailure.upstreamStatus());
    }

    // ------------------------------------------------------------ 协作式总预算

    @Test
    void failsBeforeCreatingAClientWhenTheBudgetIsAlreadyExhausted() throws IOException {
        clock.thenNanos(0L, JUST_OVER_BUDGET_NANOS);
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
        RestrictedCmsHttpClient cms = clientWith(httpClient);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> cms.fetchList(1, null));

        assertEquals(Reason.TOTAL_BUDGET_EXCEEDED, failure.reason());
        assertEquals(0, factoryCalls);
        verify(httpClient, never()).execute(any(HttpUriRequest.class));
    }

    @Test
    void allowsARequestThatEndsExactlyOnTheBudgetBoundary() throws IOException {
        clock.thenNanos(0L, RestrictedGetExecutor.TOTAL_CHECK_BUDGET_MILLIS * 1_000_000L);
        CloseableHttpClient httpClient = mockClient(responseWithEntity(new ByteArrayEntity(new byte[] {1})));

        byte[] result = clientWith(httpClient).fetchList(1, null);

        assertArrayEquals(new byte[] {1}, result);
        verify(httpClient, times(1)).close();
    }

    @Test
    void doesNotReturnAPartiallyReadBodyWhenTheBudgetExpiresWhileReading() throws IOException {
        // 前四次读数在预算内：start、连接前、响应头后、读取前；读取后的检查超预算。
        clock.thenNanos(0L, 0L, 0L, 0L, JUST_OVER_BUDGET_NANOS);
        CloseableHttpClient httpClient = mockClient(responseWithEntity(new ByteArrayEntity(new byte[] {1, 2, 3})));

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertEquals(Reason.TOTAL_BUDGET_EXCEEDED, failure.reason());
        verify(httpClient, times(1)).close();
    }

    // ------------------------------------------------------------ 关闭顺序

    @Test
    void abortsTheRequestAndClosesTheClientBeforeClosingAnOverLimitBody() throws IOException {
        CloseOrderRecorder recorder = new CloseOrderRecorder();
        RecordingBodyStream body = RecordingBodyStream.endless(recorder);
        CloseableHttpClient httpClient =
                recordingClient(recordingResponse(200, entityReturning(body), recorder), recorder);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertEquals(Reason.RESPONSE_TOO_LARGE, failure.reason());
        assertEquals(List.of(
                "client.close",
                "body.close(clientClosed=true, requestAborted=true)",
                "response.close"), recorder.events());
        assertTrue(body.isClosed());
    }

    @Test
    void abortsTheRequestAndClosesTheClientBeforeClosingTheBodyWhenTheReadFails() throws IOException {
        CloseOrderRecorder recorder = new CloseOrderRecorder();
        RecordingBodyStream body = RecordingBodyStream.failingRead(recorder,
                new IOException("connection reset by 10.1.2.3"));
        CloseableHttpClient httpClient =
                recordingClient(recordingResponse(200, entityReturning(body), recorder), recorder);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertEquals(Reason.TRANSPORT_FAILED, failure.reason());
        assertEquals(List.of(
                "client.close",
                "body.close(clientClosed=true, requestAborted=true)",
                "response.close"), recorder.events());
    }

    @Test
    void abortsTheRequestAndClosesTheClientBeforeClosingTheBodyWhenTheBudgetExpiresWhileReading()
            throws IOException {
        // start、连接前、响应头后、读取前都在预算内；读取后的那次检查超预算。
        clock.thenNanos(0L, 0L, 0L, 0L, JUST_OVER_BUDGET_NANOS);
        CloseOrderRecorder recorder = new CloseOrderRecorder();
        RecordingBodyStream body = RecordingBodyStream.of(recorder, new byte[] {1, 2, 3});
        CloseableHttpClient httpClient =
                recordingClient(recordingResponse(200, entityReturning(body), recorder), recorder);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertEquals(Reason.TOTAL_BUDGET_EXCEEDED, failure.reason());
        assertEquals(List.of(
                "client.close",
                "body.close(clientClosed=true, requestAborted=true)",
                "response.close"), recorder.events());
    }

    @Test
    void abortsTheRequestAndClosesTheClientWhenTheBodyStreamCannotBeObtained() throws IOException {
        CloseOrderRecorder recorder = new CloseOrderRecorder();
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContentLength()).thenReturn(-1L);
        when(entity.getContent()).thenThrow(new IOException("no stream for 10.1.2.3"));
        CloseableHttpClient httpClient =
                recordingClient(recordingResponse(200, entity, recorder), recorder);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertEquals(Reason.TRANSPORT_FAILED, failure.reason());
        assertEquals(List.of("client.close", "response.close"), recorder.events());
        assertTrue(recorder.requestAborted());
    }

    @Test
    void keepsTheFixedFailureCodeWhenClosingTheBodyItselfFails() throws IOException {
        CloseOrderRecorder recorder = new CloseOrderRecorder();
        RecordingBodyStream body = RecordingBodyStream.failingClose(recorder, new IOException("close failed"));
        CloseableHttpClient httpClient =
                recordingClient(recordingResponse(200, entityReturning(body), recorder), recorder);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        // 关闭异常不能把已经确定的固定码（这里是读取时判定的超限）换掉。
        assertEquals(Reason.RESPONSE_TOO_LARGE, failure.reason());
        assertEquals(List.of(
                "client.close",
                "body.close(clientClosed=true, requestAborted=true)",
                "response.close"), recorder.events());
    }

    @Test
    void abortsTheRequestAndSkipsTheBodyForEveryHeaderOnlyRejection() throws IOException {
        // 302：不跟随重定向
        CloseOrderRecorder redirect = new CloseOrderRecorder();
        HttpEntity redirectEntity = mock(HttpEntity.class);
        assertHeaderOnlyRejection(
                recordingResponse(302, redirectEntity, redirect), redirectEntity, redirect,
                Reason.UPSTREAM_STATUS);

        // 500：不读 body
        CloseOrderRecorder serverError = new CloseOrderRecorder();
        HttpEntity errorEntity = mock(HttpEntity.class);
        assertHeaderOnlyRejection(
                recordingResponse(500, errorEntity, serverError), errorEntity, serverError,
                Reason.UPSTREAM_STATUS);

        // 非 identity 的 Content-Encoding
        CloseOrderRecorder encoded = new CloseOrderRecorder();
        HttpEntity encodedEntity = mock(HttpEntity.class);
        CloseableHttpResponse encodedResponse = recordingResponse(200, encodedEntity, encoded);
        when(encodedResponse.getHeaders("Content-Encoding"))
                .thenReturn(new Header[] {new BasicHeader("Content-Encoding", "gzip")});
        assertHeaderOnlyRejection(encodedResponse, encodedEntity, encoded, Reason.CONTENT_ENCODING_NOT_IDENTITY);

        // 没有实体
        CloseOrderRecorder noEntity = new CloseOrderRecorder();
        assertHeaderOnlyRejection(recordingResponse(200, null, noEntity), null, noEntity, Reason.EMPTY_BODY);

        // Content-Length 提前声明超限
        CloseOrderRecorder declaredTooLarge = new CloseOrderRecorder();
        HttpEntity declaredEntity = mock(HttpEntity.class);
        when(declaredEntity.getContentLength()).thenReturn((long) BoundedBodyReader.MAX_BODY_BYTES + 1);
        assertHeaderOnlyRejection(
                recordingResponse(200, declaredEntity, declaredTooLarge), declaredEntity, declaredTooLarge,
                Reason.RESPONSE_TOO_LARGE);
    }

    @Test
    void closesTheBodyResponseAndClientInOrderAfterAFullyReadBody() throws IOException {
        CloseOrderRecorder recorder = new CloseOrderRecorder();
        RecordingBodyStream body = RecordingBodyStream.of(recorder, new byte[] {7, 8});
        CloseableHttpClient httpClient =
                recordingClient(recordingResponse(200, entityReturning(body), recorder), recorder);

        assertArrayEquals(new byte[] {7, 8}, clientWith(httpClient).fetchList(1, null));

        assertEquals(List.of(
                "body.close(clientClosed=false, requestAborted=false)",
                "response.close",
                "client.close"), recorder.events());
        assertFalse(recorder.requestAborted(), "读到 EOF 后不需要 abort 请求");
        assertTrue(body.isClosed());
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 提前拒绝分支（状态码 / Content-Encoding / 空实体 / 声明的 Content-Length）：不读 body，
     * 但仍要 abort 在途请求，并按 {@code client.close → response.close} 的顺序清理。
     */
    private void assertHeaderOnlyRejection(CloseableHttpResponse response, HttpEntity entity,
                                           CloseOrderRecorder recorder, Reason expectedReason) throws IOException {
        CloseableHttpClient httpClient = recordingClient(response, recorder);

        CmsHttpRequestException failure = assertThrows(CmsHttpRequestException.class,
                () -> clientWith(httpClient).fetchList(1, null));

        assertEquals(expectedReason, failure.reason());
        if (entity != null) {
            verify(entity, never()).getContent();
        }
        assertTrue(recorder.requestAborted(), "提前拒绝时也必须 abort 在途请求");
        assertEquals(List.of("client.close", "response.close"), recorder.events());
    }

    private static CloseableHttpClient recordingClient(CloseableHttpResponse response,
                                                       CloseOrderRecorder recorder) throws IOException {
        CloseableHttpClient httpClient = mock(CloseableHttpClient.class);
        when(httpClient.execute(any(HttpUriRequest.class))).thenAnswer(invocation -> {
            recorder.request = invocation.getArgument(0);
            return response;
        });
        doAnswer(invocation -> {
            recorder.clientClosed = true;
            recorder.events.add("client.close");
            return null;
        }).when(httpClient).close();
        return httpClient;
    }

    private static CloseableHttpResponse recordingResponse(int status, HttpEntity entity,
                                                           CloseOrderRecorder recorder) throws IOException {
        CloseableHttpResponse response = response(status);
        if (entity != null) {
            when(response.getEntity()).thenReturn(entity);
        }
        doAnswer(invocation -> {
            recorder.events.add("response.close");
            return null;
        }).when(response).close();
        return response;
    }

    private static HttpEntity entityReturning(InputStream content) throws IOException {
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContentLength()).thenReturn(-1L);
        when(entity.getContent()).thenReturn(content);
        return entity;
    }

    private RestrictedCmsHttpClient clientWith(CloseableHttpClient httpClient) {
        return new RestrictedCmsHttpClient(BASE_URI, policy(), dnsResolver(), clock, factory(httpClient));
    }

    private CmsHttpClientFactory factory(CloseableHttpClient httpClient) {
        return apacheDnsResolver -> {
            factoryCalls++;
            dnsResolversGivenToFactory.add(apacheDnsResolver);
            return httpClient;
        };
    }

    private ValidatedDnsResolver dnsResolver() {
        return new ValidatedDnsResolver(host -> {
            lookedUpHosts.add(host);
            return new InetAddress[] {InetAddress.getByAddress(PUBLIC_V4)};
        });
    }

    private static ExternalVideoUrlPolicy policy() {
        return new ExternalVideoUrlPolicy(
                Set.of(new ExternalVideoUrlPolicy.AllowedEndpoint(
                        "api.example.com", ExternalVideoUrlPolicy.DEFAULT_HTTPS_PORT)),
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

    /**
     * 关闭顺序记录器：client / response / body 三个替身把"我关了"按真实发生顺序记进同一个列表。
     *
     * <p>记录 body 关闭时会读当时的状态（客户端是否已关、请求是否已 abort），所以一条事件字符串就足以证明
     * {@code abort → client.close → body.close → response.close} 的先后。</p>
     */
    private static final class CloseOrderRecorder {

        private final List<String> events = new ArrayList<>();
        private HttpUriRequest request;
        private boolean clientClosed;

        List<String> events() {
            return events;
        }

        boolean requestAborted() {
            return request != null && request.isAborted();
        }
    }

    /**
     * 有状态的响应体流替身：close() 会记录自己的关闭时点，并可选择在读取/关闭时失败。
     *
     * <p>它不是 Apache 的 {@code EofSensorInputStream}，所以不复制真实的"排空剩余 body"行为；它证明的是
     * <b>顺序</b>：真实实现里 body 流与响应的 close() 都会去排空，本类保证它们排在 abort 与 client.close 之后。</p>
     */
    private static final class RecordingBodyStream extends InputStream {

        private final CloseOrderRecorder recorder;
        private final byte[] payload;
        private final boolean endless;
        private final IOException readFailure;
        private final IOException closeFailure;
        private int position;
        private boolean closed;

        private RecordingBodyStream(CloseOrderRecorder recorder, byte[] payload, boolean endless,
                                    IOException readFailure, IOException closeFailure) {
            this.recorder = recorder;
            this.payload = payload;
            this.endless = endless;
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        static RecordingBodyStream of(CloseOrderRecorder recorder, byte[] payload) {
            return new RecordingBodyStream(recorder, payload, false, null, null);
        }

        static RecordingBodyStream endless(CloseOrderRecorder recorder) {
            return new RecordingBodyStream(recorder, null, true, null, null);
        }

        static RecordingBodyStream failingRead(CloseOrderRecorder recorder, IOException failure) {
            return new RecordingBodyStream(recorder, null, false, failure, null);
        }

        static RecordingBodyStream failingClose(CloseOrderRecorder recorder, IOException failure) {
            return new RecordingBodyStream(recorder, null, true, null, failure);
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
            if (readFailure != null) {
                throw readFailure;
            }
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
        public void close() throws IOException {
            closed = true;
            recorder.events.add("body.close(clientClosed=" + recorder.clientClosed
                    + ", requestAborted=" + recorder.requestAborted() + ")");
            if (closeFailure != null) {
                throw closeFailure;
            }
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
