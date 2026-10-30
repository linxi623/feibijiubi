package com.feibijiubi.backend.integration.videosource.transport;

import com.feibijiubi.backend.integration.videosource.ExternalVideoUrlPolicy;
import com.feibijiubi.backend.integration.videosource.transport.CmsHttpRequestException.Reason;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * E2-C2：<b>受限的 CMS GET 客户端</b> —— 只对两个固定 API 形态发请求（目录一页、单个作品详情），
 * 把有界响应体交回离线解析器。没有 Spring 注解、不读配置、不注册调度、当前<b>没有生产调用方</b>。
 *
 * <h2>它能请求什么（接口形状本身就是限制）</h2>
 * <ul>
 *   <li>{@link #fetchList(int, Integer)}：{@code ?ac=list&pg=<页码>[&t=<类别 ID>]}。</li>
 *   <li>{@link #fetchDetail(String)}：{@code ?ac=detail&ids=<作品 ID>}。</li>
 *   <li>host、path、scheme 全部来自构造时<b>已批准</b>的 base URI，调用方无法覆盖；query 的参数名与顺序由
 *       本类按固定字面量拼出（{@code ac}/{@code pg}/{@code t}/{@code ids} 全部是 ASCII 固定值，插入的只有
 *       校验过的十进制数字），不接受任意 method、任意 header、任意 URL 透传。</li>
 *   <li>这不是"绝对无法请求到恶意内容"的证明：base URI 是运维批准的外部 API 端点，本类只保证请求目标
 *       不越出它、不跟随重定向、不自动去取媒体/清单/切片/key（返回的 {@code byte[]} 只交给解析器）。</li>
 * </ul>
 *
 * <h2>连接与证书</h2>
 * <ul>
 *   <li><b>DNS 校验在连接期生效</b>：{@link ValidatedDnsResolver} 被包装成 Apache 的 {@code DnsResolver}
 *       并作为 {@code BasicHttpClientConnectionManager} 的<b>构造参数</b>传入，连接用的 socket 目标就是它
 *       返回的那批地址。注意边界：本类<b>不建 socket、不做真实解析</b>，所以"钩子已接好"是结构保证，
 *       不是"已在真实网络上传过包"的证据。</li>
 *   <li><b>Host 头</b>：来自请求 URL，而本类在发请求前会确认这个 host 与批准 base 的 host 全等。</li>
 *   <li><b>TLS</b>：默认信任链 + 严格主机名校验（见 {@link RestrictedHttpClientFactory}）；原始 host 由库的
 *       {@code connectSocket} 交给 {@code createLayeredSocket}，再由
 *       {@code SSLSocketFactory.createSocket(socket, host, port, true)} 分层，所以 SNI 用的就是请求 URL
 *       的 host（<b>结构上</b>如此，本类没有自己调 {@code setServerNames}）。真实 SNI、证书链与握手结果
 *       仍需联网集成测试验证，本切片没有做。</li>
 *   <li><b>代理</b>：路由计划器只禁 HTTP 代理路由；底层原始 socket 显式用
 *       {@code new Socket(Proxy.NO_PROXY)} 创建（见 {@link RestrictedHttpClientFactory}）。这是结构事实，
 *       <b>不</b>保证透明代理、NAT 或云侧出口设备不存在。</li>
 *   <li><b>每请求新建并关闭客户端</b>：暂不池化，避免复用一条在本次 DNS 校验之前建立的连接。</li>
 * </ul>
 *
 * <h2>响应处理</h2>
 * <p>本类只决定"请求哪个 URL、带哪个固定 Accept"。发送、200/编码/空 body/超限/超预算判定、有界读取与
 * 关闭顺序<b>全部</b>由同包的 {@link RestrictedGetExecutor} 实现（M2-A 抽取），HLS 媒体清单客户端
 * {@link RestrictedManifestHttpClient} 复用<b>同一份</b>执行与清理逻辑，因此下面的契约是两条链路共同的、
 * 不是本类自己的一份实现。</p>
 * <ul>
 *   <li>只接受 <b>200</b>：3xx 一律按 {@link Reason#UPSTREAM_STATUS} 拒绝（不跟随），其它非 200 同样拒绝；
 *       非 200 时<b>不读</b>响应体，只保留状态整数。</li>
 *   <li>{@code Content-Encoding} 不是 {@code identity}（含多值与 {@code gzip, identity} 这种组合）就拒绝，
 *       绝不做隐式解压。</li>
 *   <li>响应体上限 512 KiB：{@code Content-Length} 声明超限时直接拒绝，读取时仍按<b>实际字节数</b>再判一次，
 *       正好 512 KiB 通过、多 1 字节失败；空 body 拒绝。</li>
 *   <li><b>不看 Content-Type</b>：不渲染、不猜编码。已知上游会返回 {@code text/html;charset=utf-8} 但
 *       body 是 JSON，是否接受由解析器决定，本类不因此改写或转码任何字节。</li>
 *   <li><b>关闭顺序是契约的一部分</b>：只要 body 没有读到 EOF（提前拒绝、超限、超预算、读取 IO 失败都算），
 *       就先 abort 在途请求、再关闭客户端（连带关闭连接管理器与底层 socket），最后才关闭 body 流与响应。
 *       反过来先关 body 或先关响应都不安全：{@code EofSensorInputStream#close()} 与
 *       {@code ResponseEntityProxy#close()} 都会去"把剩余 body 读干"，遇到无限 body 就会一直读下去。
 *       成功读完 EOF 时则按 body 流 → 响应 → 客户端的顺序正常关闭。每条分支都会关闭 body 流、响应与客户端，
 *       关闭时的异常也不会覆盖已经确定的固定失败码。</li>
 * </ul>
 *
 * <h2>失败码边界</h2>
 * <p>已经确定的 {@link CmsHttpRequestException}（本类自己判定的状态码/编码/空 body/超限/超预算，以及工厂
 * 报的 {@link Reason#CLIENT_INIT_FAILED}）原样上抛；{@code IOException} 与其它 {@code RuntimeException}
 * （包括客户端构造/执行期的运行时异常）统一折叠成 {@link Reason#TRANSPORT_FAILED}，不带原文与 cause；
 * {@code Error} 不捕获。这只是固定码层面的归一，不是“本地初始化故障全能分类”的承诺。</p>
 *
 * <h2>超时</h2>
 * <p>连接 5 s、socket 读取 10 s、申请连接 1 s 是 Apache 的固定超时；另外有一次请求级的
 * <b>20 s 总检查预算</b>，在连接前、收到响应头后、每次读取前后、返回前检查。这是<b>协作式检查</b>，不是硬
 * 超时：它不能打断 DNS 查询、TLS 握手或阻塞中的 {@code read}，也不会把已经过期的 body 返回给调用方。</p>
 *
 * <h2>脱敏与线程安全</h2>
 * <p>异常只带固定原因码（外加非 200 的状态整数），不带 URL、host、地址、端口、上游 reason phrase 或原始异常；
 * 响应体/响应头不写日志。实例不可变、每个请求各自新建客户端，因此可以被多个线程共享（不共享 per-request
 * 可变状态）。外部 Apache 组件自身的 wire debug 日志不受本类控制。</p>
 */
public final class RestrictedCmsHttpClient {

    /** 目录页码上限：只允许 {@code 1..MAX_LIST_PAGE}，避免拿一个离谱的页码去敲上游。 */
    public static final int MAX_LIST_PAGE = 100_000;

    /** 作品 ID 的最大长度：与解析器的作品身份列宽（191）一致。 */
    public static final int MAX_WORK_ID_LENGTH = 191;

    /** 本客户端固定的 {@code Accept}；执行、判定与清理都在共享的 {@link RestrictedGetExecutor} 里。 */
    private static final String ACCEPT_JSON = "application/json";

    private static final String ACTION_LIST_QUERY = "ac=list";
    private static final String ACTION_DETAIL_QUERY = "ac=detail";
    private static final String PAGE_PARAMETER = "&pg=";
    private static final String CATEGORY_PARAMETER = "&t=";
    private static final String IDS_PARAMETER = "&ids=";

    private static final String PAGE_REQUIRED_MESSAGE =
            "页码必须是 1.." + MAX_LIST_PAGE + " 之间的整数";
    private static final String CATEGORY_REQUIRED_MESSAGE = "类别 ID 必须是非负整数，或省略";
    private static final String WORK_ID_REQUIRED_MESSAGE =
            "作品 ID 必须是长度 1.." + MAX_WORK_ID_LENGTH + " 的十进制正整数（不允许前导零）";

    /**
     * 批准 base 的 {@code scheme://authority/rawPath}：query 由本类按固定参数追加。
     *
     * <p>scheme 统一小写成 {@code https}（策略比较本来就是大小写无关的），raw path 原样保留、
     * 不重编码也不规范化。raw path 必须显式存在（root {@code /} 合法）并以<b>单个</b> {@code /} 开头：
     * host-only 与 {@code //} 开头的 base 都在构造期拒绝，本类<b>不</b>替调用方补写路径。</p>
     */
    private final String basePrefix;
    private final String baseHost;
    private final String baseRawPath;
    private final RestrictedGetExecutor executor;

    /**
     * 生产构造器：真实受限客户端 + 系统时钟。
     *
     * @param approvedBaseUri 运维批准的 HTTPS API base URI（例如
     *                        {@code https://api.example.com/api.php/provide/vod/}）；必须通过 {@code urlPolicy}、
     *                        <b>不带 query、userinfo、片段</b>，并且<b>显式带路径</b>（root {@code /} 合法，
     *                        但不能是 host-only，也不能以 {@code //} 开头），否则构造期失败
     * @param urlPolicy       地址策略（HTTPS + 精确 host:port 允许列表 + 长度上限），不允许为 {@code null}
     * @param dnsResolver     已校验的解析器，不允许为 {@code null}：本类没有默认解析实现
     * @throws IllegalArgumentException base 不被接受、或任一参数为 {@code null}；文案不含 URL
     */
    public RestrictedCmsHttpClient(String approvedBaseUri,
                                   ExternalVideoUrlPolicy urlPolicy,
                                   ValidatedDnsResolver dnsResolver) {
        this(approvedBaseUri, urlPolicy, dnsResolver, CmsRequestDeadline.SYSTEM_CLOCK,
                RestrictedHttpClientFactory.INSTANCE);
    }

    /**
     * 离线单测用构造器：注入受控时钟与客户端工厂。包私有 —— 生产路径不会经过它，所以无法用假的工厂绕过
     * DNS 校验或 TLS 配置（见 {@link CmsHttpClientFactory}）。
     */
    RestrictedCmsHttpClient(String approvedBaseUri,
                            ExternalVideoUrlPolicy urlPolicy,
                            ValidatedDnsResolver dnsResolver,
                            CmsRequestDeadline.Clock clock,
                            CmsHttpClientFactory clientFactory) {
        if (urlPolicy == null) {
            throw new IllegalArgumentException("URL 策略不能为空");
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
        URI base = validateBaseUri(approvedBaseUri, urlPolicy);
        this.basePrefix = base.getScheme().toLowerCase(Locale.ROOT) + "://"
                + base.getRawAuthority() + base.getRawPath();
        this.baseHost = base.getHost();
        this.baseRawPath = base.getRawPath();
        this.executor = new RestrictedGetExecutor(dnsResolver, clock, clientFactory);
    }

    /**
     * 取目录的一页。
     *
     * @param page       页码 {@code 1..}{@value #MAX_LIST_PAGE}
     * @param categoryId 类别 ID，{@code null} 表示不带类别过滤，非 {@code null} 时必须非负
     * @return 有界的响应体，交给离线解析器
     * @throws IllegalArgumentException 参数越界；此时不会创建任何客户端
     * @throws CmsHttpRequestException  上游失败；只带固定原因码（与非 200 的状态整数）
     */
    public byte[] fetchList(int page, Integer categoryId) {
        requirePage(page);
        requireCategoryId(categoryId);
        return execute(buildListQuery(page, categoryId));
    }

    /**
     * 取单个作品的详情。
     *
     * @param workId 上游作品 ID：十进制正整数，长度 {@code 1..}{@value #MAX_WORK_ID_LENGTH}，不允许前导零
     *               （前导零会让同一个作品出现两种写法，身份就不唯一了）
     * @return 有界的响应体，交给离线解析器
     * @throws IllegalArgumentException 参数不是规范的正整数；此时不会创建任何客户端
     * @throws CmsHttpRequestException  上游失败；只带固定原因码（与非 200 的状态整数）
     */
    public byte[] fetchDetail(String workId) {
        requireWorkId(workId);
        return execute(ACTION_DETAIL_QUERY + IDS_PARAMETER + workId);
    }

    private byte[] execute(String fixedQuery) {
        return executor.execute(buildTarget(fixedQuery), ACCEPT_JSON);
    }

    /**
     * 用批准的 base 前缀 + 本类构造的 query 拼出请求目标，并确认 host/path 没有被改写。
     *
     * <p>base 的 raw path 原样保留（不重编码、不规范化）；query 只含固定 ASCII 参数名与校验过的十进制值。</p>
     */
    private URI buildTarget(String fixedQuery) {
        URI target;
        try {
            target = new URI(basePrefix + "?" + fixedQuery);
        } catch (URISyntaxException e) {
            // base 与 query 都已在构造/参数校验阶段限定为可打印 ASCII，走到这里说明前置不变量被破坏。
            throw new IllegalStateException("构造受控请求目标失败");
        }
        boolean hostPreserved = baseHost != null && baseHost.equalsIgnoreCase(target.getHost());
        boolean pathPreserved = baseRawPath.equals(target.getRawPath());
        boolean queryIsFixed = fixedQuery.equals(target.getRawQuery());
        if (!hostPreserved || !pathPreserved || !queryIsFixed) {
            throw new IllegalStateException("受控请求目标的 host 或 path 与批准的 base 不一致");
        }
        return target;
    }

    private static URI validateBaseUri(String approvedBaseUri, ExternalVideoUrlPolicy urlPolicy) {
        URI base;
        try {
            base = urlPolicy.validate(approvedBaseUri);
        } catch (ExternalVideoUrlPolicy.RejectedUrlException e) {
            // 策略异常的文案本身已经是固定的；这里换成接线期错误，并只附带原因码（不含 URL）。
            throw new IllegalArgumentException("批准的 API base URI 未通过 URL 策略：" + e.reason().name());
        }
        if (base.getRawQuery() != null) {
            throw new IllegalArgumentException("批准的 API base URI 不允许带 query：query 只能由本客户端按固定参数构造");
        }
        if (base.getRawFragment() != null || base.getRawUserInfo() != null) {
            // 策略已经拒绝这两种写法，这里是防御性二次确认（策略实现变动时不至于悄悄放行）。
            throw new IllegalArgumentException("批准的 API base URI 不允许带 userinfo 或片段");
        }
        // 本组件自己的 base 契约：path 必须显式存在，而且只有一个 '/' 开头。
        // 不自动补写 '/'：host-only 的 base 会让“批准路径”变成隐式的；"//x" 在部分客户端里会被当成
        // authority 分隔，语义不唯一。这是保守的 API 契约，不是对“已有调用方在发非法请求”的断言
        // （Apache 自己可能会把空 path 补成 '/'）。
        String rawPath = base.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            throw new IllegalArgumentException(
                    "批准的 API base URI 必须显式带路径（root '/' 合法）：本客户端不会自动补写 '/'");
        }
        if (rawPath.charAt(0) != '/' || rawPath.startsWith("//")) {
            throw new IllegalArgumentException("批准的 API base URI 路径必须以单个 '/' 开头");
        }
        return base;
    }

    private static String buildListQuery(int page, Integer categoryId) {
        StringBuilder query = new StringBuilder(ACTION_LIST_QUERY).append(PAGE_PARAMETER).append(page);
        if (categoryId != null) {
            query.append(CATEGORY_PARAMETER).append(categoryId.intValue());
        }
        return query.toString();
    }

    private static void requirePage(int page) {
        if (page < 1 || page > MAX_LIST_PAGE) {
            throw new IllegalArgumentException(PAGE_REQUIRED_MESSAGE);
        }
    }

    private static void requireCategoryId(Integer categoryId) {
        if (categoryId != null && categoryId < 0) {
            throw new IllegalArgumentException(CATEGORY_REQUIRED_MESSAGE);
        }
    }

    private static void requireWorkId(String workId) {
        if (workId == null || workId.isEmpty() || workId.length() > MAX_WORK_ID_LENGTH
                || workId.charAt(0) == '0') {
            throw new IllegalArgumentException(WORK_ID_REQUIRED_MESSAGE);
        }
        for (int i = 0; i < workId.length(); i++) {
            char c = workId.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(WORK_ID_REQUIRED_MESSAGE);
            }
        }
    }
}
