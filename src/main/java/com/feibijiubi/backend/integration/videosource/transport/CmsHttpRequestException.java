package com.feibijiubi.backend.integration.videosource.transport;

/**
 * E2-C2：受限 CMS GET 传输层的<b>固定失败码</b>异常。
 *
 * <p>只有固定原因码，{@code getMessage()} 是原因码的固定文案；对上游返回非 200 的情况额外只保留
 * <b>状态整数</b>（{@link #upstreamStatus()}），<b>不</b>保留上游 reason phrase、响应头、响应体、
 * 请求 URL、主机名、IP 地址，也<b>不</b>携带 cause。理由与 {@link DnsResolutionException} 相同：这个异常
 * 会被后续业务/审计/日志广泛传播，而 {@code UnknownHostException}/{@code SSLException} 一类的文案里通常
 * 带着主机名、地址与端口；把它们作为 cause 或拼进文案，等于把上游地址写进各处。</p>
 *
 * <h2>它不说明什么</h2>
 * <p>本异常不区分"上游不存在"/"网络不可达"/"TLS 校验失败"/"读取中断"，这些都折叠成
 * {@link Reason#TRANSPORT_FAILED}。真正的原因只能来自受控的诊断（例如带权限的运维抓包/上游状态页），
 * 不来自本异常。它也不表示"已经证明上游是安全的"：本异常只描述一次受限 GET 的失败，不构成任何网络
 * 安全验收。</p>
 *
 * <p>唯一的例外是 {@link Reason#CLIENT_INIT_FAILED}：本地客户端初始化（默认 SSL 上下文）失败有自己
 * 的固定码，同样不带信任库路径、环境变量或 cause。<b>它不是完整的本地故障分类</b> —— 初始化期抛出的
 * 其它运行时异常仍会由 {@link RestrictedGetExecutor} 统一折叠成
 * {@link Reason#TRANSPORT_FAILED}，所以不要据原因码反推本地环境到底发生了什么。</p>
 *
 * <h2>历史命名（M2-A 起共用）</h2>
 * <p>类名里的 {@code Cms} 是历史命名：它最初只为 CMS 目录/详情客户端而建。M2-A 把受限 GET 的执行、响应判定
 * 与清理抽到 {@link RestrictedGetExecutor} 后，HLS 媒体清单客户端
 * （{@link RestrictedManifestHttpClient}）<b>也抛本异常、也用同一套原因码</b>。这样只保留一套固定码，而不
 * 新增一层平行的媒体异常；原因码的含义与文案不变（例如 {@link Reason#UPSTREAM_STATUS} 对两个客户端都是
 * “上游返回非 200 状态码”）。</p>
 */
public final class CmsHttpRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** {@link #upstreamStatus()} 在"没有上游状态码"时的取值。 */
    public static final int NO_UPSTREAM_STATUS = -1;

    private final Reason reason;
    private final int upstreamStatus;

    /**
     * 只允许同包（受限传输层的实现与被测有界读取器）构造：原因码是固定契约，调用方只读。
     *
     * @param reason 固定原因码；文案取自 {@link Reason#description()}
     */
    CmsHttpRequestException(Reason reason) {
        this(reason, NO_UPSTREAM_STATUS);
    }

    /**
     * @param reason         固定原因码
     * @param upstreamStatus 上游 HTTP 状态码；仅 {@link Reason#UPSTREAM_STATUS} 使用
     */
    CmsHttpRequestException(Reason reason, int upstreamStatus) {
        super(reason.description());
        this.reason = reason;
        this.upstreamStatus = upstreamStatus;
    }

    /** 固定的失败原因码。 */
    public Reason reason() {
        return reason;
    }

    /**
     * 上游返回的状态码（只对 {@link Reason#UPSTREAM_STATUS} 有值）。
     *
     * @return 状态整数；没有上游状态码时是 {@link #NO_UPSTREAM_STATUS}（{@code -1}）
     */
    public int upstreamStatus() {
        return upstreamStatus;
    }

    /**
     * 固定的失败原因码。{@link #name()} 是对外稳定的机器码，{@link #description()} 是固定中文文案，
     * 两者都不含 URL、主机名、地址、端口或原始异常内容。
     */
    public enum Reason {

        /** 连接、TLS 握手、DNS 校验拒绝或读取任一阶段失败；原始异常（含 {@code UnknownHostException}）不向上透出。 */
        TRANSPORT_FAILED("上游传输失败（连接/TLS/读取），未保留原始异常"),

        /**
         * 本地受限客户端初始化失败（例如默认 SSL 上下文建不起来）。发生在发出任何请求之前，
         * 文案固定且不带 cause。
         */
        CLIENT_INIT_FAILED("本地受限客户端初始化失败"),

        /** 上游返回了 200 以外的状态码（含 3xx：本客户端不跟随重定向）。见 {@link #upstreamStatus()}。 */
        UPSTREAM_STATUS("上游返回非 200 状态码"),

        /** 响应体超过 {@code 512 KiB} 上限（提前按 Content-Length 拒绝，或读取时按实际字节数拒绝）。 */
        RESPONSE_TOO_LARGE("响应体超过 512 KiB 上限"),

        /** 响应没有实体，或实体长度为 0。 */
        EMPTY_BODY("响应体为空"),

        /** 响应声明了非 {@code identity} 的 Content-Encoding（含多值），本客户端不做隐式解压。 */
        CONTENT_ENCODING_NOT_IDENTITY("响应使用了非 identity 的 Content-Encoding"),

        /** 单次请求的总检查预算耗尽；读取中途超预算时不会返回已经过期的 body。 */
        TOTAL_BUDGET_EXCEEDED("单次请求总检查预算耗尽");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }
}
