package com.feibijiubi.backend.integration.videosource.transport;

import com.feibijiubi.backend.integration.videosource.transport.CmsHttpRequestException.Reason;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.client.CloseableHttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * M2-A：<b>受限 GET 的唯一执行器</b> —— 一次请求的发送、响应判定、有界读取与清理顺序都只在这里实现。
 *
 * <p>存在的理由只有一条：{@link RestrictedCmsHttpClient}（CMS 目录/详情）与
 * {@link RestrictedManifestHttpClient}（HLS 媒体清单）必须走<b>同一份</b>安全 HTTP 执行逻辑。复制一份
 * "差不多"的实现迟早会分叉（比如一边漏了 {@code identity} 判定，或把关闭顺序写反），所以两个客户端都只负责
 * "请求目标与固定 header 由谁决定"，执行与清理全部委托给本类。</p>
 *
 * <h2>本类不决定什么</h2>
 * <ul>
 *   <li><b>不拼 URL、不校验 host</b>：目标 {@link URI} 由调用方在构造/参数校验阶段用
 *       {@code ExternalVideoUrlPolicy} 与自己的接口形状定死，本类只按它发一次请求，不重编码、不重排 query、
 *       不跟随重定向。</li>
 *   <li><b>不接受任意 method / 任意 header</b>：只发 {@code GET}，只带两个由调用方给出的固定值
 *       （{@code Accept} 与固定的 {@code Accept-Encoding: identity}）。没有 Cookie/Authorization 透传入口。</li>
 *   <li><b>不决定请求次数</b>：一次 {@link #execute(URI, String)} 就是一次请求；没有重试、没有 HEAD 探测、
 *       没有后续请求。是否再发一次由调用方的接口形状决定，本类不替它做。</li>
 * </ul>
 *
 * <h2>响应契约（两个客户端共用，因此只在这里写一次）</h2>
 * <ul>
 *   <li>只接受 <b>200</b>：3xx 一律按 {@link Reason#UPSTREAM_STATUS} 拒绝（不跟随），其它非 200 同样拒绝；
 *       非 200 时<b>不读</b>响应体，只保留状态整数。</li>
 *   <li>{@code Content-Encoding} 不是 {@code identity}（含多值与 {@code gzip, identity} 这种组合）就拒绝，
 *       绝不做隐式解压。</li>
 *   <li>响应体上限 512 KiB：{@code Content-Length} 声明超限时直接拒绝，读取时仍按<b>实际字节数</b>再判一次；
 *       空 body 拒绝。</li>
 *   <li><b>不看 Content-Type</b>：不渲染、不猜编码。上游返回什么 MIME 都不改变字节，是否接受由解析器决定。</li>
 *   <li><b>关闭顺序是契约的一部分</b>：只要 body 没有读到 EOF（提前拒绝、超限、超预算、读取 IO 失败都算），
 *       就先 abort 在途请求、再关闭客户端（连带关闭连接管理器与底层 socket），最后才关闭 body 流与响应。
 *       反过来先关 body 或先关响应都不安全：{@code EofSensorInputStream#close()} 与
 *       {@code ResponseEntityProxy#close()} 都会去"把剩余 body 读干"，遇到无限 body 就会一直读下去。
 *       成功读完 EOF 时则按 body 流 → 响应 → 客户端的顺序正常关闭。每条分支都会关闭 body 流、响应与客户端，
 *       关闭时的异常也不会覆盖已经确定的固定失败码。</li>
 * </ul>
 *
 * <h2>失败码与超时</h2>
 * <p>本类自己判定的固定码（状态码/编码/空 body/超限/超预算）原样上抛；工厂报的
 * {@link Reason#CLIENT_INIT_FAILED} 也原样上抛；{@code IOException} 与其它 {@code RuntimeException}
 * 统一折叠成 {@link Reason#TRANSPORT_FAILED}，不带原文与 cause；{@code Error} 不捕获。连接 5 s、
 * socket 读取 10 s、申请连接 1 s 是 Apache 的固定超时；另外有一次请求级的
 * {@value #TOTAL_CHECK_BUDGET_MILLIS} ms <b>协作式</b>总检查预算，在连接前、收到响应头后、每次读取前后、
 * 返回前检查。它不能打断 DNS 查询、TLS 握手或阻塞中的 {@code read}，也不会把已经过期的 body 返回给调用方。</p>
 *
 * <h2>接线边界</h2>
 * <p>本类<b>包私有</b>：它不是包外公共入口；当前两个客户端先完成 URL/参数校验。同包代码仍可直接调用，包可见性并非安全沙箱，不应绕过校验
 * 直接发一个任意 GET。{@link CmsHttpClientFactory} 与 {@link CmsRequestDeadline.Clock} 是给离线单测用的
 * seam，生产构造器只传 {@link RestrictedHttpClientFactory#INSTANCE} 与系统时钟。</p>
 */
final class RestrictedGetExecutor {

    /** 单次请求的总检查预算（毫秒）；协作式检查，不是 SLA。 */
    static final long TOTAL_CHECK_BUDGET_MILLIS = 20_000L;

    private static final int HTTP_OK = 200;

    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";
    private static final String HEADER_CONTENT_ENCODING = "Content-Encoding";
    private static final String ENCODING_IDENTITY = "identity";

    private static final String TARGET_REQUIRED_MESSAGE = "受限 GET 的目标地址不能为空";
    private static final String ACCEPT_REQUIRED_MESSAGE = "受限 GET 的固定 Accept 值不能为空";

    private final CmsRequestDeadline.Clock clock;
    private final CmsHttpClientFactory clientFactory;
    private final DnsResolver apacheDnsResolver;

    /**
     * @param dnsResolver   已校验的解析器，不允许为 {@code null}：本类没有默认解析实现
     * @param clock         时间源，不允许为 {@code null}
     * @param clientFactory 客户端工厂，不允许为 {@code null}；生产路径是
     *                      {@link RestrictedHttpClientFactory#INSTANCE}
     */
    RestrictedGetExecutor(ValidatedDnsResolver dnsResolver,
                          CmsRequestDeadline.Clock clock,
                          CmsHttpClientFactory clientFactory) {
        if (dnsResolver == null) {
            throw new IllegalArgumentException("DNS 校验器不能为空：本类没有默认解析实现");
        }
        if (clock == null) {
            throw new IllegalArgumentException("时间源不能为空");
        }
        if (clientFactory == null) {
            throw new IllegalArgumentException("客户端工厂不能为空");
        }
        this.clock = clock;
        this.clientFactory = clientFactory;
        this.apacheDnsResolver = RestrictedHttpClientFactory.apacheDnsResolver(dnsResolver);
    }

    /**
     * 对已经定死的目标发一次受限 GET，返回有界的响应体。
     *
     * @param target       调用方已校验的请求目标（原样交给 {@link HttpGet}，不重编码）
     * @param acceptHeader 调用方固定的 {@code Accept} 值（例如 {@code application/json}）
     * @return 长度在 {@code 1..512 KiB} 之间的响应体副本
     * @throws IllegalArgumentException 目标或 Accept 为空；此时不会创建任何客户端
     * @throws CmsHttpRequestException  上游失败；只带固定原因码（与非 200 的状态整数）
     */
    byte[] execute(URI target, String acceptHeader) {
        if (target == null) {
            throw new IllegalArgumentException(TARGET_REQUIRED_MESSAGE);
        }
        if (acceptHeader == null || acceptHeader.isBlank()) {
            throw new IllegalArgumentException(ACCEPT_REQUIRED_MESSAGE);
        }
        CmsRequestDeadline deadline = CmsRequestDeadline.start(clock, TOTAL_CHECK_BUDGET_MILLIS);

        HttpGet request = new HttpGet(target);
        // 只带这两个固定 header：没有 Cookie / Authorization / 任何调用方 header。
        request.setHeader(HEADER_ACCEPT, acceptHeader);
        request.setHeader(HEADER_ACCEPT_ENCODING, ENCODING_IDENTITY);

        CloseableHttpClient client = null;
        CloseableHttpResponse response = null;
        InputStream content = null;
        boolean bodyFullyRead = false;
        try {
            deadline.check();
            client = clientFactory.newClient(apacheDnsResolver);
            response = client.execute(request);
            deadline.check();

            int status = response.getStatusLine().getStatusCode();
            if (status != HTTP_OK) {
                // 3xx 也不跟随、不读 body：只记住状态整数。
                throw new CmsHttpRequestException(Reason.UPSTREAM_STATUS, status);
            }
            requireIdentityContentEncoding(response.getHeaders(HEADER_CONTENT_ENCODING));

            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw new CmsHttpRequestException(Reason.EMPTY_BODY);
            }
            if (entity.getContentLength() > BoundedBodyReader.MAX_BODY_BYTES) {
                // Content-Length 可以提前拒绝；但读取时仍按实际字节数再判一次（声明可能撒谎）。
                throw new CmsHttpRequestException(Reason.RESPONSE_TOO_LARGE);
            }
            // body 流由本方法自己关闭，不放 try-with-resources：try-with-resources 会先关流、再进 finally，
            // 于是超限/超预算/IO 失败时 close() 先于 abort，EofSensorInputStream 会去排空剩余 body。
            content = entity.getContent();
            if (content == null) {
                // 有实体但没有流：和“没有实体”一样当空 body，不把 NPE 折叠成传输失败。
                throw new CmsHttpRequestException(Reason.EMPTY_BODY);
            }
            byte[] body = BoundedBodyReader.read(content, deadline);
            deadline.check();
            bodyFullyRead = true;
            return body;
        } catch (CmsHttpRequestException e) {
            // 已经确定的固定码原样上抛：状态码/编码/空 body/超限/超预算/本地初始化失败都不重新分类。
            throw e;
        } catch (IOException | RuntimeException e) {
            // 连接/DNS 校验拒绝/TLS/读取失败，以及构造/执行期的运行时异常：统一固定码，不保留原文与 cause。
            // Error（含线程中止）不在这里捕获，中断标志也不会被本类改写。
            throw new CmsHttpRequestException(Reason.TRANSPORT_FAILED);
        } finally {
            if (bodyFullyRead) {
                // 已读到 EOF：正常关闭，body 流 → 响应 → 客户端。
                closeContentQuietly(content);
                closeResponseQuietly(response);
                closeClientQuietly(client);
            } else {
                // 没读到 EOF（提前拒绝 / 超限 / 超预算 / 读取失败 / 流都没拿到）：
                // 先 abort 在途请求，再关客户端（连带关闭连接管理器与底层 socket），
                // 最后才关 body 流与响应 —— 它们的 close() 会尝试把剩余 body 读干。
                abortQuietly(request);
                closeClientQuietly(client);
                closeContentQuietly(content);
                closeResponseQuietly(response);
            }
        }
    }

    /**
     * {@code Content-Encoding} 必须是"没有"或单个 {@code identity}：本类不做解压，也不猜编码。
     *
     * <p>多值（多个头字段，或一个字段里出现逗号）明确拒绝，而不是"取第一个继续"。</p>
     */
    private static void requireIdentityContentEncoding(Header[] encodings) {
        if (encodings == null || encodings.length == 0) {
            return;
        }
        if (encodings.length > 1) {
            throw new CmsHttpRequestException(Reason.CONTENT_ENCODING_NOT_IDENTITY);
        }
        String value = encodings[0] == null ? null : encodings[0].getValue();
        if (value == null || value.indexOf(',') >= 0
                || !ENCODING_IDENTITY.equalsIgnoreCase(value.trim())) {
            throw new CmsHttpRequestException(Reason.CONTENT_ENCODING_NOT_IDENTITY);
        }
    }

    private static void abortQuietly(HttpGet request) {
        try {
            request.abort();
        } catch (RuntimeException e) {
            // abort 只是"尽快取消"，失败也不能覆盖已经确定的固定失败码。
        }
    }

    private static void closeContentQuietly(InputStream content) {
        if (content == null) {
            return;
        }
        try {
            content.close();
        } catch (IOException | RuntimeException e) {
            // 关闭失败不能覆盖已经确定的固定失败码；此时客户端与响应仍会被关闭。
        }
    }

    private static void closeResponseQuietly(CloseableHttpResponse response) {
        if (response == null) {
            return;
        }
        try {
            response.close();
        } catch (IOException | RuntimeException e) {
            // 关闭失败不能覆盖已经确定的固定失败码；连接管理器随后也会被关闭。
        }
    }

    private static void closeClientQuietly(CloseableHttpClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException | RuntimeException e) {
            // 同上：清理失败不影响对外契约。
        }
    }
}
