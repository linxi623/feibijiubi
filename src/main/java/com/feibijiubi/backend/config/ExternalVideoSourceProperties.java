package com.feibijiubi.backend.config;

import com.feibijiubi.backend.integration.videosource.ExternalVideoUrlPolicy;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * M3-A：外部视频来源的<b>部署批准配置</b> —— 只读注册表
 * {@code com.feibijiubi.backend.service.video.external.ExternalVideoSourceRegistry} 的唯一输入。
 *
 * <h2>这一片做什么、不做什么</h2>
 * <ul>
 *   <li>只描述"哪些来源被运维批准、其 API base 与允许端点是什么"。本类<b>不注册 bean</b>：只有
 *       {@code @ConfigurationProperties}，没有 {@code @Component}，也没有被写进
 *       {@code BackendApplication} 的 {@code @EnableConfigurationProperties}，而应用没有
 *       {@code @ConfigurationPropertiesScan}，所以应用启动时不会扫描到它。接线是下一片的事。</li>
 *   <li><b>不发网络请求</b>：不做 DNS 解析、不建 HTTP 客户端、不探测媒体、不启动线程或定时任务。
 *       {@code enabled=false} 与 {@code preview-enabled=false} 都不会触发任何外部调用。</li>
 *   <li>不新增 controller / mapper / Redis key / 读取接口，也不伪造任何"成功"API。</li>
 * </ul>
 *
 * <h2>字段语义与边界</h2>
 * <ul>
 *   <li>{@code preview-enabled} 默认 <b>false</b>：关闭时注册表拒绝一切预览请求，这是"默认不开放"的
 *       显式开关，不是安全边界的替代品。</li>
 *   <li>{@code max-concurrent-requests}（1..8，默认 2）与 {@code per-admin-requests-per-minute}
 *       （1..120，默认 30）<b>只做取值校验并保存</b>：本片<b>没有</b>并发闸门，也<b>没有</b>限流实现，
 *       因此不能把这两个值说成"已经具备并发保护 / 限流"。</li>
 *   <li>每个来源的 {@code code} 是<b>部署白名单引用键</b>（字符串，<b>不是</b>数据库主键），必须匹配
 *       {@code [a-z][a-z0-9_-]{0,63}}：大小写敏感、<b>不 trim</b>、不做任何规范化。首尾空格会直接校验
 *       失败，因此不会出现"配置写 {@code abc }、库表 {@code utf8mb4_bin}(PAD SPACE) 把它当成
 *       {@code abc}"这种静默合并（E1-A 的身份列语义）。</li>
 *   <li>{@code api-base} 必须是已批准 API 端点上的 HTTPS 地址：无 query、无片段、无 userinfo，且
 *       <b>显式带</b>以单个 {@code /} 开头的路径（与 E2-C2 的 CMS base 同一标准，不额外允许
 *       {@code http}）。长度上限 {@value #MAX_API_BASE_LENGTH} 与 {@code video_source.base_url}
 *       的 {@code VARCHAR(500)} 对齐。</li>
 *   <li>{@code api-allowed-endpoints} 与 {@code media-allowed-endpoints} 是两套<b>独立</b>的精确
 *       {@code host:port} 允许列表：允许写相同端点，但代码<b>不会</b>自动合并，也不会互相兜底。
 *       API 允许列表<b>不能为空</b>（否则 api-base 永远无法被批准）；媒体允许列表<b>可以</b>为空，
 *       空表示"该来源不支持媒体探测"，此时注册表不构造媒体策略，<b>绝不</b>拿 API 允许列表顶替。
 *       每套最多 {@value #MAX_ALLOWED_ENDPOINTS_PER_ALLOWLIST} 个端点，防止一次配置误放大成一大片
 *       放行范围。</li>
 *   <li>所有来源（含 {@code enabled=false}）都必须通过校验：禁用是运行期开关，不是"跳过校验"的借口，
 *       一份以后要启用的配置必须现在就是有效的。默认（不写 {@code sources}）合法：零来源就是零预览
 *       能力。</li>
 * </ul>
 *
 * <h2>校验与脱敏</h2>
 * <p>{@link #validate()} 是公开方法，可以脱离 Spring 单测；同时标注 {@code @PostConstruct}，因此将来
 * 真正注册成 bean 时启动期就会校验。注册表构造时会<b>再主动调一次</b>，所以手工 {@code new} 出来的
 * properties 也无法绕过校验。</p>
 * <p>失败一律是 {@link IllegalStateException}，文案只有"字段路径 + 固定原因码"，<b>不含</b> api-base、
 * host、名称或 code 的取值，也不带 cause（{@code URI} 解析异常的文案会把原始 URL 拼进去）。本类与
 * 内部条目类都<b>不</b>覆写 {@code toString()}（沿用 {@link Object} 的"类名 + 哈希"），避免日志里
 * 意外打印 base 或允许列表。</p>
 */
@ConfigurationProperties(prefix = ExternalVideoSourceProperties.PREFIX)
public class ExternalVideoSourceProperties {

    /** 配置前缀。 */
    public static final String PREFIX = "app.external-video-source";

    /** 一次部署最多配置多少个来源：这是防误配置的硬上限，不是容量旋钮。 */
    public static final int MAX_CONFIGURED_SOURCES = 32;

    /** 单套允许列表最多多少个精确端点。 */
    public static final int MAX_ALLOWED_ENDPOINTS_PER_ALLOWLIST = 32;

    /** 来源编码长度上限，与 {@code video_source.code} 的 {@code VARCHAR(64)} 一致。 */
    public static final int MAX_SOURCE_CODE_LENGTH = 64;

    /** 来源展示名长度上限，与 {@code video_source.name} 的 {@code VARCHAR(100)} 一致。 */
    public static final int MAX_SOURCE_NAME_LENGTH = 100;

    /** API base 长度上限，与 {@code video_source.base_url} 的 {@code VARCHAR(500)} 一致。 */
    public static final int MAX_API_BASE_LENGTH = 500;

    /**
     * 媒体地址长度上限：只用于构造媒体 URL 策略，<b>不是</b>任何表的列宽
     * （媒体地址/线路表在 E1 后续阶段，本片不建表）。它是工程上限，不是产品承诺。
     */
    public static final int MAX_MEDIA_URL_LENGTH = 2_048;

    public static final int MIN_MAX_CONCURRENT_REQUESTS = 1;
    public static final int MAX_MAX_CONCURRENT_REQUESTS = 8;
    public static final int MIN_PER_ADMIN_REQUESTS_PER_MINUTE = 1;
    public static final int MAX_PER_ADMIN_REQUESTS_PER_MINUTE = 120;

    /** 固定原因码：全部是对外稳定的机器码，文案里只会出现这些码，不会出现配置取值。 */
    public static final String REASON_SOURCES_MISSING = "SOURCES_MISSING";
    public static final String REASON_SOURCES_TOO_MANY = "SOURCES_TOO_MANY";
    public static final String REASON_SOURCE_MISSING = "SOURCE_MISSING";
    public static final String REASON_SOURCE_CODE_MISSING = "SOURCE_CODE_MISSING";
    public static final String REASON_SOURCE_CODE_INVALID = "SOURCE_CODE_INVALID";
    public static final String REASON_SOURCE_CODE_DUPLICATE = "SOURCE_CODE_DUPLICATE";
    public static final String REASON_SOURCE_NAME_MISSING = "SOURCE_NAME_MISSING";
    public static final String REASON_SOURCE_NAME_TOO_LONG = "SOURCE_NAME_TOO_LONG";
    public static final String REASON_API_BASE_MISSING = "API_BASE_MISSING";
    public static final String REASON_API_BASE_TOO_LONG = "API_BASE_TOO_LONG";
    public static final String REASON_API_ALLOWED_ENDPOINTS_MISSING = "API_ALLOWED_ENDPOINTS_MISSING";
    public static final String REASON_ENDPOINT_INVALID = "ENDPOINT_INVALID";
    public static final String REASON_ENDPOINTS_TOO_MANY = "ENDPOINTS_TOO_MANY";
    public static final String REASON_API_BASE_NOT_APPROVED = "API_BASE_NOT_APPROVED";
    public static final String REASON_MAX_CONCURRENT_REQUESTS_OUT_OF_RANGE = "MAX_CONCURRENT_REQUESTS_OUT_OF_RANGE";
    public static final String REASON_PER_ADMIN_REQUESTS_PER_MINUTE_OUT_OF_RANGE =
            "PER_ADMIN_REQUESTS_PER_MINUTE_OUT_OF_RANGE";

    /**
     * 来源编码：小写字母开头，之后只允许小写字母、数字、下划线和连字符，总长 1..64。
     *
     * <p>正则本身就限定了长度（{@code 1..64}），{@code requireCode} 里再加一次 {@code MAX_SOURCE_CODE_LENGTH}
     * 比较是为了把“列宽”写成显式守卫；<b>不</b>做 trim、<b>不</b>做大小写折叠 ——
     * 大写的 {@code AppleCms} 与带空格的 {@code apple-cms } 都必须被拒绝，而不是被悄悄修正成一个
     * "看起来能用"的编码。</p>
     */
    private static final Pattern SOURCE_CODE_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    /**
     * 预览总开关，默认 <b>false</b>：关闭时注册表对任何来源都返回"预览未启用"，不区分来源是否存在。
     */
    private boolean previewEnabled;

    /** 部署批准的来源列表，默认空列表（零来源 = 零预览能力，是合法配置）。 */
    private List<Source> sources = new ArrayList<>();

    /**
     * 单个管理员同时允许的并发请求数，默认 2，取值 1..8。
     *
     * <p>本片<b>只校验并保存</b>：没有任何并发闸门实现，别把它读成"已经有并发保护"。</p>
     */
    private int maxConcurrentRequests = 2;

    /**
     * 单个管理员每分钟允许的请求数，默认 30，取值 1..120。
     *
     * <p>本片<b>只校验并保存</b>：没有任何限流实现，别把它读成"已经限流"。</p>
     */
    private int perAdminRequestsPerMinute = 30;

    /**
     * 启动期校验（同时供单测直接调用）。
     *
     * <p>顺序固定：全局参数 → 列表本身 → 逐条来源（code → 名称 → API base 与允许列表 → 媒体允许列表）。
     * 所有来源都会校验，包括 {@code enabled=false} 的条目。</p>
     *
     * @throws IllegalStateException 任一规则不通过；文案只有字段路径与固定原因码
     */
    @PostConstruct
    public void validate() {
        if (maxConcurrentRequests < MIN_MAX_CONCURRENT_REQUESTS
                || maxConcurrentRequests > MAX_MAX_CONCURRENT_REQUESTS) {
            throw fail(PREFIX + ".max-concurrent-requests",
                    REASON_MAX_CONCURRENT_REQUESTS_OUT_OF_RANGE);
        }
        if (perAdminRequestsPerMinute < MIN_PER_ADMIN_REQUESTS_PER_MINUTE
                || perAdminRequestsPerMinute > MAX_PER_ADMIN_REQUESTS_PER_MINUTE) {
            throw fail(PREFIX + ".per-admin-requests-per-minute",
                    REASON_PER_ADMIN_REQUESTS_PER_MINUTE_OUT_OF_RANGE);
        }
        if (sources == null) {
            // null 列表只可能来自代码/绑定异常；空列表才表示"没有配置来源"。
            throw fail(PREFIX + ".sources", REASON_SOURCES_MISSING);
        }
        if (sources.size() > MAX_CONFIGURED_SOURCES) {
            throw fail(PREFIX + ".sources", REASON_SOURCES_TOO_MANY);
        }

        Set<String> seenCodes = new HashSet<>();
        for (int index = 0; index < sources.size(); index++) {
            Source source = sources.get(index);
            String field = PREFIX + ".sources[" + index + "]";
            if (source == null) {
                throw fail(field, REASON_SOURCE_MISSING);
            }
            requireCode(field + ".code", source.getCode());
            if (!seenCodes.add(source.getCode())) {
                throw fail(field + ".code", REASON_SOURCE_CODE_DUPLICATE);
            }
            requireName(field + ".name", source.getName());
            requireApprovedApiPolicy(field + ".api-base", source.getApiBase(),
                    source.getApiAllowedEndpoints());
            mediaPolicy(field + ".media-allowed-endpoints", source.getMediaAllowedEndpoints());
        }
    }

    // ------------------------------------------------------------ 可复用的校验/构造 seam

    /**
     * 校验一条来源的 API base 与 API 允许列表，并返回批准后的地址策略。
     *
     * <p>注册表用同一个方法构造描述符，因此"校验通过"与"真正被批准使用的策略"不会出现两份实现。</p>
     *
     * @param apiBaseField     用于报错的字段路径（不含取值）
     * @param apiBase          待批准的 HTTPS base
     * @param rawApiEndpoints  精确 {@code host:port} 允许列表，<b>不能为空</b>
     * @return 不可变的地址策略（允许列表已深拷贝）
     * @throws IllegalStateException base 或允许列表不合法；文案不含 base、host 或取值
     */
    public static ExternalVideoUrlPolicy requireApprovedApiPolicy(String apiBaseField,
                                                                 String apiBase,
                                                                 Collection<String> rawApiEndpoints) {
        if (apiBase == null || apiBase.isBlank()) {
            throw fail(apiBaseField, REASON_API_BASE_MISSING);
        }
        if (apiBase.length() > MAX_API_BASE_LENGTH) {
            throw fail(apiBaseField, REASON_API_BASE_TOO_LONG);
        }
        Set<ExternalVideoUrlPolicy.AllowedEndpoint> endpoints =
                parseEndpoints(apiBaseField, rawApiEndpoints, true);
        ExternalVideoUrlPolicy policy =
                new ExternalVideoUrlPolicy(endpoints, MAX_API_BASE_LENGTH);

        URI base;
        try {
            base = policy.validate(apiBase);
        } catch (ExternalVideoUrlPolicy.RejectedUrlException e) {
            // 策略的文案已经是固定的；这里只附加它的固定原因码，既不带 cause 也不带原始 URL。
            throw fail(apiBaseField, REASON_API_BASE_NOT_APPROVED + ":" + e.reason().name());
        }
        // 片段与 userinfo 已由策略拒绝；这里再显式确认 query 与路径形状（E2-C2 的 base 契约）。
        if (base.getRawQuery() != null) {
            throw fail(apiBaseField, REASON_API_BASE_NOT_APPROVED + ":QUERY_PRESENT");
        }
        String rawPath = base.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            throw fail(apiBaseField, REASON_API_BASE_NOT_APPROVED + ":PATH_REQUIRED");
        }
        if (rawPath.charAt(0) != '/' || rawPath.startsWith("//")) {
            throw fail(apiBaseField, REASON_API_BASE_NOT_APPROVED + ":PATH_NOT_SINGLE_SLASH");
        }
        return policy;
    }

    /**
     * 校验媒体允许列表，并<b>只在非空时</b>构造媒体地址策略。
     *
     * <p>空列表/null 表示"该来源不支持媒体探测"，返回 {@link Optional#empty()}。这里<b>绝不</b>回退到
     * API 允许列表：那会让"没批准媒体端点"悄悄变成"批准了 API 端点上的媒体"，是两个不同的授权。</p>
     *
     * @param field            用于报错的字段路径（不含取值）
     * @param rawMediaEndpoints 精确 {@code host:port} 允许列表；null 与空集合等价
     * @return 非空时为不可变媒体策略
     * @throws IllegalStateException 端点语法不合法或数量超限
     */
    public static Optional<ExternalVideoUrlPolicy> mediaPolicy(String field,
                                                               Collection<String> rawMediaEndpoints) {
        Set<ExternalVideoUrlPolicy.AllowedEndpoint> endpoints =
                parseEndpoints(field, rawMediaEndpoints, false);
        if (endpoints.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ExternalVideoUrlPolicy(endpoints, MAX_MEDIA_URL_LENGTH));
    }

    /**
     * 解析精确 {@code host:port} 允许列表。
     *
     * <p>端点格式沿用 {@link ExternalVideoUrlPolicy.AllowedEndpoint} 的构造格式：{@code host:port}，
     * host 由策略自己校验（拒绝 IP 字面量、保留名、尾点、单标签名等），port 必须是 1..65535 的十进制。
     * host 大小写会被策略规范化成小写，因此 {@code API.example.invalid:443} 与
     * {@code api.example.invalid:443} 是<b>同一个</b>端点（DNS 主机名本来就大小写无关），在集合里
     * 会合并成一条 —— 这是同一端点的两种写法，不是两个授权。</p>
     *
     * @param field   用于报错的字段路径
     * @param raw     原始字符串集合；{@code required=false} 时 null/空集合返回空集合
     * @param required true 表示空集合本身即错误
     */
    public static Set<ExternalVideoUrlPolicy.AllowedEndpoint> parseEndpoints(String field,
                                                                            Collection<String> raw,
                                                                            boolean required) {
        if (raw == null || raw.isEmpty()) {
            if (required) {
                throw fail(field, REASON_API_ALLOWED_ENDPOINTS_MISSING);
            }
            return Set.of();
        }
        if (raw.size() > MAX_ALLOWED_ENDPOINTS_PER_ALLOWLIST) {
            throw fail(field, REASON_ENDPOINTS_TOO_MANY);
        }
        Set<ExternalVideoUrlPolicy.AllowedEndpoint> endpoints = new LinkedHashSet<>();
        for (String rawEndpoint : raw) {
            if (rawEndpoint == null) {
                throw fail(field, REASON_ENDPOINT_INVALID);
            }
            endpoints.add(parseEndpoint(field, rawEndpoint));
        }
        return Set.copyOf(endpoints);
    }

    private static ExternalVideoUrlPolicy.AllowedEndpoint parseEndpoint(String field, String rawEndpoint) {
        int separator = rawEndpoint.lastIndexOf(':');
        if (separator <= 0 || separator == rawEndpoint.length() - 1) {
            throw fail(field, REASON_ENDPOINT_INVALID);
        }
        String portText = rawEndpoint.substring(separator + 1);
        if (portText.length() > 5 || (portText.length() > 1 && portText.charAt(0) == '0')) {
            throw fail(field, REASON_ENDPOINT_INVALID);
        }
        int port = 0;
        for (int i = 0; i < portText.length(); i++) {
            char c = portText.charAt(i);
            if (c < '0' || c > '9') {
                throw fail(field, REASON_ENDPOINT_INVALID);
            }
            port = port * 10 + (c - '0');
        }
        try {
            return new ExternalVideoUrlPolicy.AllowedEndpoint(
                    rawEndpoint.substring(0, separator), port);
        } catch (IllegalArgumentException e) {
            // AllowedEndpoint 的文案只有原因码，但仍统一换成我们自己的固定码，不依赖它的措辞。
            throw fail(field, REASON_ENDPOINT_INVALID);
        }
    }

    private static void requireCode(String field, String code) {
        if (code == null || code.isBlank()) {
            throw fail(field, REASON_SOURCE_CODE_MISSING);
        }
        // 长度与 video_source.code 的 VARCHAR(64) 对齐；正则本身也限定长度，这里是显式的列宽守卫。
        if (code.length() > MAX_SOURCE_CODE_LENGTH || !SOURCE_CODE_PATTERN.matcher(code).matches()) {
            throw fail(field, REASON_SOURCE_CODE_INVALID);
        }
    }

    private static void requireName(String field, String name) {
        if (name == null || name.isBlank()) {
            throw fail(field, REASON_SOURCE_NAME_MISSING);
        }
        if (name.length() > MAX_SOURCE_NAME_LENGTH) {
            throw fail(field, REASON_SOURCE_NAME_TOO_LONG);
        }
    }

    private static IllegalStateException fail(String field, String reasonCode) {
        return new IllegalStateException(field + " " + reasonCode);
    }

    // ------------------------------------------------------------ Boot binder 需要的读写方法

    public boolean isPreviewEnabled() {
        return previewEnabled;
    }

    public void setPreviewEnabled(boolean previewEnabled) {
        this.previewEnabled = previewEnabled;
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public int getPerAdminRequestsPerMinute() {
        return perAdminRequestsPerMinute;
    }

    public void setPerAdminRequestsPerMinute(int perAdminRequestsPerMinute) {
        this.perAdminRequestsPerMinute = perAdminRequestsPerMinute;
    }

    /**
     * 一个部署批准的外部来源。
     *
     * <p>刻意<b>不</b>覆写 {@code toString()}（沿用 {@link Object} 的"类名 + 哈希"）：本类持有 API base
     * 与允许列表，一旦被打进日志就等于把服务端地址清单抄进了日志。也不要给它加 Lombok
     * {@code @Data}，那会自动生成包含全部字段的 {@code toString()}。</p>
     *
     * <p>{@code enabled} 默认 <b>false</b>：批准一个来源的配置与"现在就对管理员开放它"是两件事，
     * 后者需要显式打开。</p>
     */
    public static class Source {

        /** 是否允许预览，默认 false。 */
        private boolean enabled;

        /** 部署白名单引用键（字符串，不是数据库主键）；大小写敏感、不 trim。 */
        private String code;

        /** 展示名，仅服务端与（将来的）管理员界面使用。 */
        private String name;

        /** 上游 API base，<b>仅服务端使用，不对外返回</b>。 */
        private String apiBase;

        /** API 精确 {@code host:port} 允许列表，不能为空。 */
        private Set<String> apiAllowedEndpoints = new LinkedHashSet<>();

        /**
         * 媒体精确 {@code host:port} 允许列表，可以为空。
         *
         * <p>空 = 不支持媒体探测；与 API 允许列表<b>独立</b>，不会自动合并或互相兜底。</p>
         */
        private Set<String> mediaAllowedEndpoints = new LinkedHashSet<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public Set<String> getApiAllowedEndpoints() {
            return apiAllowedEndpoints;
        }

        public void setApiAllowedEndpoints(Set<String> apiAllowedEndpoints) {
            this.apiAllowedEndpoints = apiAllowedEndpoints;
        }

        public Set<String> getMediaAllowedEndpoints() {
            return mediaAllowedEndpoints;
        }

        public void setMediaAllowedEndpoints(Set<String> mediaAllowedEndpoints) {
            this.mediaAllowedEndpoints = mediaAllowedEndpoints;
        }
    }
}
