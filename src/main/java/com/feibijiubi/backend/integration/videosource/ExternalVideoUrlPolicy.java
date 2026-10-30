package com.feibijiubi.backend.integration.videosource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * E2-A：外部视频地址的<b>纯语法</b>策略 —— 只做字符与 URI 语法判断，不发网络请求、不做 DNS 解析、
 * 不是 Spring bean、不读配置、不打日志，当前也没有任何业务调用方。
 *
 * <h2>它保证什么</h2>
 * <ul>
 *   <li>只接受 {@code https}：{@code http}、{@code ftp}、{@code javascript:}、{@code data:}、协议相对
 *       写法（{@code //host/...}）与缺失 scheme 的写法一律拒绝。</li>
 *   <li>host 必须是精确的 ASCII 点分域名，并且与构造时传入的<b>受信部署配置</b>端点（host + port）全等
 *       匹配：不做后缀匹配、不做通配符、不自动放行子域。待校验 URL 只能"去匹配"允许列表，永远不能
 *       反过来扩充它。</li>
 *   <li>URL 未写端口时按 {@value #DEFAULT_HTTPS_PORT} 处理，并要求允许列表里存在该端口的条目；
 *       非 {@value #DEFAULT_HTTPS_PORT} 端口必须有对应条目。</li>
 *   <li>拒绝 IP 字面量（IPv4 / IPv6，以及 {@code 0x7f.0.0.1}、{@code 2130706433}、{@code 127.1} 这类
 *       数字/十六进制缩写变体）、本地与内网常用保留名称、userinfo、片段标识、反斜杠、空白与控制字符
 *       （包括 {@code %0d%0a}、{@code %00}、{@code %7f} 这类百分号编码形式）、非法转义、
 *       非规范 authority 与超长 URL。IP 缩写可能先被 {@code URI} 判为 {@code AUTHORITY_INVALID}，
 *       或在 host 校验阶段返回 {@code HOST_IS_IP_LITERAL}；这类输入的契约是拒绝，不承诺统一分类。</li>
 *   <li>拒绝信息固定：{@link RejectedUrlException} 的 message 只有固定文案（不含原始 URL、host、query
 *       或端口），也<b>不</b>携带 cause —— {@link URISyntaxException} 的 message 会把原始 URL 拼进去。</li>
 * </ul>
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li><b>不改写输入</b>：不 normalize、不排序、不重新编码 query/path，也不重复解码百分号转义。
 *       校验通过后返回的 {@link URI} 的 {@code toString()} 与传入的原始字符串完全一致，因此签名 query
 *       （{@code %2F}、{@code %3B}、{@code +}、参数顺序）原样保留。</li>
 *   <li><b>不猜 IDN</b>：输入必须是纯 ASCII；国际化域名必须先由调用方转成 punycode（{@code xn--...}）
 *       再交给本类，本类不替你转、也不接受未转写的 Unicode 域名。</li>
 *   <li><b>不是完整的 SSRF 防护</b>：允许列表只能保证"我们只把经批准域名的地址交出去"，<b>不能</b>
 *       保证该域名解析到公网地址。批准域名仍可能被劫持或本身就指向私网；{@code HTTP} 客户端必须自己
 *       做连接级 IP 固定/校验（拒绝 loopback、私有、link-local、CGNAT、ULA、组播）、逐跳重定向策略、
 *       响应体与超时上限以及部署侧出口限制。HLS 清单里的分片/密钥地址由浏览器自行请求，本类不解析、
 *       不校验。</li>
 * </ul>
 *
 * <h2>检查顺序</h2>
 * <p>空值 → 长度 → 可打印 ASCII → 编码控制字符 → URI 语法 → scheme → 片段 → authority 存在 →
 * userinfo → host 存在 → host 语法/IP/保留名称 → 端口范围 → authority 规范形式 → 允许列表。
 * 顺序是契约的一部分：每个输入只会得到下面 {@link RejectionReason} 里的一个固定原因码。</p>
 */
public final class ExternalVideoUrlPolicy {

    /** HTTPS 默认端口：URL 未显式写端口时按这个值处理。 */
    public static final int DEFAULT_HTTPS_PORT = 443;

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /** 域名总长上限（DNS 工程上限，不是协议承诺）。 */
    private static final int MAX_HOST_LENGTH = 253;

    /** 单个 label 长度上限。 */
    private static final int MAX_LABEL_LENGTH = 63;

    private static final String REQUIRED_SCHEME = "https";

    /** 单个 DNS label：字母/数字开头结尾，中间允许连字符；下划线等一律不接受。 */
    private static final Pattern LABEL_PATTERN =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    /**
     * 纯十进制/点分写法：{@code 1.2.3.4}、{@code 127.1}、{@code 2130706433} 都是同一个 IPv4 字面量的
     * 不同写法，浏览器会当 IP 解析，因此整串只含数字和点时一律按 IP 字面量拒绝。
     */
    private static final Pattern NUMERIC_HOST_PATTERN = Pattern.compile("[0-9.]+");

    /** 十六进制缩写 label：{@code 0x7f000001}、{@code 0x7f.0.0.1} 里的 {@code 0x7f}。 */
    private static final Pattern HEX_LITERAL_LABEL_PATTERN = Pattern.compile("0x[0-9a-f]*");

    /** 本地/内网常用保留名称：<b>精确</b>全等匹配，不是后缀匹配。 */
    private static final Set<String> SPECIAL_HOST_NAMES = Set.of(
            "localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback",
            "broadcasthost", "intranet", "internal", "lan");

    /**
     * 保留给私有用途的名称后缀（RFC 6761 的 {@code .localhost}/{@code .local}、RFC 8375 的
     * {@code .home.arpa}、ICANN 保留的 {@code .internal}）。
     *
     * <p>这里出现后缀匹配<b>只用于拒绝</b>，永远不会用于放行 —— 放行只走允许列表的全等匹配。</p>
     */
    private static final List<String> SPECIAL_HOST_SUFFIXES =
            List.of(".localhost", ".local", ".internal", ".home.arpa");

    private final Set<AllowedEndpoint> allowedEndpoints;
    private final int maxUrlLength;

    /**
     * @param allowedEndpoints 受信部署配置给出的允许端点集合；<b>不允许为空</b>（空集合是接线错误，
     *                         不是运行期状态，因此直接在构造期失败）。集合会被不可变复制，之后调用方
     *                         再改自己的集合不会影响本对象；集合含 null 元素时由 Set.copyOf 抛出
     *                         NullPointerException，同样属于接线期配置错误
     * @param maxUrlLength     该用途允许的最大长度（按 {@code String#length()} 计，含 scheme）；
     *                         播放地址与外链封面的具体数字由调用方按自己的列宽/响应上限决定，
     *                         本类不内置这两个业务数字
     */
    public ExternalVideoUrlPolicy(Collection<AllowedEndpoint> allowedEndpoints, int maxUrlLength) {
        if (allowedEndpoints == null || allowedEndpoints.isEmpty()) {
            throw new IllegalArgumentException("允许端点集合不能为空：空集合即拒绝所有地址");
        }
        if (maxUrlLength <= 0) {
            throw new IllegalArgumentException("URL 最大长度必须是正整数");
        }
        this.allowedEndpoints = Set.copyOf(allowedEndpoints);
        this.maxUrlLength = maxUrlLength;
    }

    /**
     * 校验一条外部视频地址（播放直链 MP4、HLS 清单等）。
     *
     * <p>只看语法，<b>不</b>看扩展名：{@code .mp4}、{@code .m3u8}、{@code .ts} 与完全没有扩展名的地址
     * 一视同仁，格式判断不属于本类。校验通过返回的 {@link URI} 就是输入字符串本身
     * （{@code toString()} 与入参完全一致），调用方不应再改写它。</p>
     *
     * @param rawUrl 原始地址；可以为 {@code null}（按 {@link RejectionReason#EMPTY_URL} 拒绝）
     * @return 与入参完全一致的 {@link URI}
     * @throws RejectedUrlException 任何一条规则不通过；message 是固定文案，不含原始 URL
     */
    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw reject(RejectionReason.EMPTY_URL);
        }
        if (rawUrl.length() > maxUrlLength) {
            throw reject(RejectionReason.URL_TOO_LONG);
        }
        requirePrintableAscii(rawUrl);
        requireNoEncodedControlCharacters(rawUrl);

        URI uri = parse(rawUrl);

        String scheme = uri.getScheme();
        if (scheme == null || !REQUIRED_SCHEME.equals(scheme.toLowerCase(Locale.ROOT))) {
            throw reject(RejectionReason.SCHEME_NOT_HTTPS);
        }
        // 原串里的 '#' 一定是片段起点，用原始字符判断可以避开 URI 对"空片段"的归一化差异。
        if (rawUrl.indexOf('#') >= 0) {
            throw reject(RejectionReason.FRAGMENT_PRESENT);
        }

        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null || rawAuthority.isEmpty()) {
            throw reject(RejectionReason.MISSING_HOST);
        }
        // 用原始 authority 判断 '@'，而不是依赖 getRawUserInfo()：空 userinfo 可能被 URI 归一化成 null。
        if (rawAuthority.indexOf('@') >= 0) {
            throw reject(RejectionReason.USERINFO_PRESENT);
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw reject(RejectionReason.AUTHORITY_INVALID);
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        RejectionReason hostReason = hostRejection(lowerHost);
        if (hostReason != null) {
            throw reject(hostReason);
        }

        int uriPort = uri.getPort();
        int effectivePort;
        if (uriPort == -1) {
            effectivePort = DEFAULT_HTTPS_PORT;
        } else if (uriPort < MIN_PORT || uriPort > MAX_PORT) {
            throw reject(RejectionReason.PORT_INVALID);
        } else {
            effectivePort = uriPort;
        }
        // authority 必须恰好是 host 或 host:port：这样空端口、前导零端口、带转义的 host 都会被拒绝，
        // 而不是被 URI 悄悄当成别的意思。
        String expectedAuthority = uriPort == -1 ? host : host + ":" + uriPort;
        if (!rawAuthority.equalsIgnoreCase(expectedAuthority)) {
            throw reject(RejectionReason.AUTHORITY_INVALID);
        }

        if (!allowedEndpoints.contains(new AllowedEndpoint(lowerHost, effectivePort))) {
            throw reject(RejectionReason.ENDPOINT_NOT_ALLOWED);
        }
        return uri;
    }

    /** 允许端点集合的只读视图（{@code Set.copyOf} 的结果，不可修改）。 */
    public Set<AllowedEndpoint> allowedEndpoints() {
        return allowedEndpoints;
    }

    private static URI parse(String rawUrl) {
        try {
            return new URI(rawUrl);
        } catch (URISyntaxException e) {
            // 刻意不保留 cause：URISyntaxException 的 message 会拼进原始 URL。
            throw reject(RejectionReason.MALFORMED_URL);
        }
    }

    /**
     * 只允许可打印 ASCII：控制字符、空格、DEL、非 ASCII（含未转 punycode 的 Unicode 域名）和反斜杠都拒绝。
     *
     * <p>反斜杠要单独拒绝：浏览器会把 {@code https://host\@evil} 当成 {@code https://host/@evil}，
     * 而不同 HTTP 客户端对它的处理并不一致。</p>
     */
    private static void requirePrintableAscii(String rawUrl) {
        for (int i = 0; i < rawUrl.length(); i++) {
            char c = rawUrl.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == '\\') {
                throw reject(RejectionReason.ILLEGAL_CHARACTER);
            }
        }
    }

    /**
     * 拒绝百分号编码的 C0 控制字符与 DEL（{@code %00}-{@code %1f}、{@code %7f}），即 CRLF/NUL 注入的
     * 常见编码形式。
     *
     * <p>只检查一层：不做 URL 解码，也不拒绝 {@code %20}（编码后的空格在签名 query 里是正常写法，
     * 客户端发出去的仍然是 {@code %20} 而不是裸空格）。非法转义（如 {@code %zz}）留给
     * {@link URI} 判定为 {@link RejectionReason#MALFORMED_URL}。</p>
     */
    private static void requireNoEncodedControlCharacters(String rawUrl) {
        for (int i = 0; i + 2 < rawUrl.length(); i++) {
            if (rawUrl.charAt(i) != '%') {
                continue;
            }
            int high = hexValue(rawUrl.charAt(i + 1));
            int low = hexValue(rawUrl.charAt(i + 2));
            if (high < 0 || low < 0) {
                continue;
            }
            int value = (high << 4) | low;
            if (value < 0x20 || value == 0x7F) {
                throw reject(RejectionReason.ENCODED_CONTROL_CHARACTER);
            }
        }
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /**
     * host 语法与"不得是 IP 字面量 / 保留名称"的判断，入参必须是已经小写化的 host。
     *
     * @return {@code null} 表示通过
     */
    private static RejectionReason hostRejection(String lowerHost) {
        if (lowerHost.isEmpty()) {
            return RejectionReason.MISSING_HOST;
        }
        if (lowerHost.charAt(0) == '[' || lowerHost.indexOf(':') >= 0) {
            return RejectionReason.HOST_IS_IP_LITERAL;
        }
        if (lowerHost.endsWith(".")) {
            return RejectionReason.HOST_ILLEGAL;
        }
        if (NUMERIC_HOST_PATTERN.matcher(lowerHost).matches()) {
            return RejectionReason.HOST_IS_IP_LITERAL;
        }
        if (SPECIAL_HOST_NAMES.contains(lowerHost)) {
            return RejectionReason.HOST_IS_SPECIAL_NAME;
        }
        for (String suffix : SPECIAL_HOST_SUFFIXES) {
            if (lowerHost.endsWith(suffix)) {
                return RejectionReason.HOST_IS_SPECIAL_NAME;
            }
        }
        if (lowerHost.length() > MAX_HOST_LENGTH) {
            return RejectionReason.HOST_ILLEGAL;
        }
        String[] labels = lowerHost.split("\\.", -1);
        for (String label : labels) {
            if (HEX_LITERAL_LABEL_PATTERN.matcher(label).matches()) {
                return RejectionReason.HOST_IS_IP_LITERAL;
            }
            if (label.isEmpty()
                    || label.length() > MAX_LABEL_LENGTH
                    || !LABEL_PATTERN.matcher(label).matches()) {
                return RejectionReason.HOST_ILLEGAL;
            }
        }
        if (labels.length < 2) {
            // 单 label 名称（intranet、myhost、printer ...）不是可白名单的公网域名，只按语法拒绝；
            // 已识别的保留名称在上面已经给出更具体的原因码。
            return RejectionReason.HOST_ILLEGAL;
        }
        return null;
    }

    private static RejectedUrlException reject(RejectionReason reason) {
        return new RejectedUrlException(reason);
    }

    /**
     * 受信部署配置里的一个允许端点：精确 host + 精确 port。
     *
     * <p>构造时就把 host 规范化成小写并校验语法：配置本身也是要校验的输入（配置里写错一个 IP 或尾点域名，
     * 就应该在接线时失败，而不是等到运行时才发现允许列表根本不生效）。</p>
     */
    public record AllowedEndpoint(String host, int port) {

        public AllowedEndpoint {
            if (host == null) {
                throw new IllegalArgumentException("允许端点的 host 不能为空");
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            RejectionReason rejection = hostRejection(lowerHost);
            if (rejection != null) {
                throw new IllegalArgumentException("允许端点的 host 不合法：" + rejection.name());
            }
            if (port < MIN_PORT || port > MAX_PORT) {
                throw new IllegalArgumentException("允许端点的 port 必须在 1..65535");
            }
            host = lowerHost;
        }
    }

    /**
     * 校验失败的固定异常：只有原因码与固定文案，<b>不含</b>原始 URL、host、query 或端口，也没有 cause。
     */
    public static final class RejectedUrlException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final RejectionReason reason;

        private RejectedUrlException(RejectionReason reason) {
            super(reason.description());
            this.reason = reason;
        }

        public RejectionReason reason() {
            return reason;
        }
    }

    /**
     * 固定的拒绝原因码。{@link #name()} 是对外稳定的机器码，{@link #description()} 是固定的中文文案
     * （两者都不含任何 URL 片段）。
     */
    public enum RejectionReason {

        EMPTY_URL("URL 为空"),
        URL_TOO_LONG("URL 超过允许的最大长度"),
        ILLEGAL_CHARACTER("URL 含空白、控制字符、非 ASCII 字符或反斜杠"),
        ENCODED_CONTROL_CHARACTER("URL 含百分号编码的控制字符"),
        MALFORMED_URL("URL 语法不合法"),
        SCHEME_NOT_HTTPS("URL 必须使用 https 且必须带 scheme"),
        FRAGMENT_PRESENT("URL 不允许携带片段标识"),
        USERINFO_PRESENT("URL 不允许携带用户信息"),
        MISSING_HOST("URL 缺少主机名"),
        AUTHORITY_INVALID("URL 的 authority 不是规范的 host 或 host:port"),
        HOST_ILLEGAL("主机名不是合法的 ASCII 点分域名"),
        HOST_IS_IP_LITERAL("主机名不允许是 IP 字面量"),
        HOST_IS_SPECIAL_NAME("主机名不允许是本地或内网保留名称"),
        PORT_INVALID("端口不在 1..65535"),
        ENDPOINT_NOT_ALLOWED("URL 的 host:port 不在允许端点列表内");

        private final String description;

        RejectionReason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }
}
