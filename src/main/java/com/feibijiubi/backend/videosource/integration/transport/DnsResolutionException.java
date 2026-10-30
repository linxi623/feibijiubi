package com.feibijiubi.backend.videosource.integration.transport;

/**
 * E2-C1：DNS 解析结果校验失败的固定异常。
 *
 * <p>只带一个固定原因码：{@code getMessage()} 是原因码的固定文案，<b>不含</b>主机名、IP 地址、
 * 查询实现抛出的异常文案，也<b>不</b>携带 cause。原因很具体：{@code UnknownHostException} 会把主机名
 * 拼进 message，{@code SocketException} 会把地址与端口拼进去，把它们作为 cause 或文案向上透出，
 * 就等于把上游主机名和地址写进日志/响应/审计。真正的排查信息应来自受信查询实现自己的日志（那里有上下文，
 * 也有权限控制），而不是这个会被广泛传播的异常。</p>
 *
 * <h2>它不说明什么</h2>
 * <p>本异常只表示"这次地址查询的结果没有通过 {@link ValidatedDnsResolver} 的校验契约"，
 * <b>不</b>表示主机名不存在、上游不可用、地址不可播放，也不构成"连接期已固定地址"的证据：
 * 校验通过之后仍然要用同一批地址去建立连接，中间再解析一次就没有意义了。</p>
 */
public final class DnsResolutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Reason reason;

    /**
     * 只允许同包的 {@link ValidatedDnsResolver} 构造：原因码由校验实现决定，调用方只读。
     *
     * @param reason 固定原因码；文案取自 {@link Reason#description()}
     */
    DnsResolutionException(Reason reason) {
        super(reason.description());
        this.reason = reason;
    }

    /** 固定的失败原因码。 */
    public Reason reason() {
        return reason;
    }

    /**
     * 固定的失败原因码。{@link #name()} 是对外稳定的机器码，{@link #description()} 是固定的中文文案，
     * 两者都不含主机名、地址或原始异常内容。
     */
    public enum Reason {

        /** 主机名没有通过 {@code ExternalVideoUrlPolicy.AllowedEndpoint} 的语法检查（IP 字面量、保留名、单标签名等）。 */
        HOST_NOT_ALLOWED("主机名不是可接受的公网域名写法"),

        /** 受信查询实现抛出了受检异常（或自身的运行期异常）；原始异常内容不透出。 */
        LOOKUP_FAILED("地址查询失败"),

        /** 查询过程中被中断；中断标志已由调用方重新设置。 */
        LOOKUP_INTERRUPTED("地址查询被中断"),

        /** 查询返回 {@code null} 或空数组。 */
        LOOKUP_EMPTY("地址查询没有返回任何地址"),

        /** 查询结果数组里含 {@code null} 元素。 */
        NULL_ADDRESS("地址查询结果包含空元素"),

        /** 查询结果里至少有一个地址不是公网候选；整批拒绝，不挑出公网地址继续。 */
        NON_PUBLIC_ADDRESS("地址查询结果包含非公网候选地址");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }
}
