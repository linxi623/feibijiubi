package com.feibijiubi.backend.videosource.integration.transport;

import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy;
import com.feibijiubi.backend.videosource.integration.transport.DnsResolutionException.Reason;

import java.net.InetAddress;

/**
 * E2-C1：<b>把受信地址查询的结果整批校验</b>后交出去 —— 没有内置联网查询实现，但会调用注入的
 * 查询实现（该实现可能联网）。本类不建 socket、不是 Spring bean，不读配置、不打日志、不缓存。
 *
 * <p>它只做三件事，顺序固定：</p>
 * <ol>
 *   <li><b>先做主机名语法检查</b>：复用 {@link ExternalVideoUrlPolicy.AllowedEndpoint}（端口取
 *       {@link ExternalVideoUrlPolicy#DEFAULT_HTTPS_PORT}），因此 IP 字面量（IPv4/IPv6/数字与十六进制
 *       缩写变体）、{@code localhost}/{@code .local}/{@code .internal} 这类保留名、单标签名、尾点域名、
 *       含 userinfo/路径的串都会在<b>任何查询发生之前</b>被拒绝。</li>
 *   <li><b>调用受信查询</b>：{@link AddressLookup} 由调用方注入，本类<b>没有</b>默认实现，也没有
 *       {@code InetAddress.getAllByName} 之类的兜底 —— 想联网必须显式把联网实现传进来。查询入参是
 *       调用方传入的<b>原始 host 字符串</b>（不做小写化、去空白、加尾点等改写）。</li>
 *   <li><b>整批校验</b>：结果 {@code null}/空、含 {@code null} 元素、或<b>任意</b>一个地址不是
 *       {@link PublicAddressPolicy} 的公网候选，就整批拒绝，绝不会"挑出公网地址继续用"。全部通过时返回
 *       一个独立副本。</li>
 * </ol>
 *
 * <h2>调用方必须知道的边界（不读这段就会用错）</h2>
 * <ul>
 *   <li><b>本类不是端点允许列表的替代品</b>：{@code ExternalVideoUrlPolicy} 仍然必须先跑，它回答
 *       "这个 host:port 是否在部署白名单里"；本类只回答"这一次查询拿到的地址是不是公网候选"。</li>
 *   <li><b>先检查、再用普通 HTTP 客户端连接是不安全的</b>：{@code HttpClient} 连接时会自己再解析一次
 *       DNS，两次解析之间可以变（DNS rebinding / TOCTOU），于是"检查过的地址"和"实际连上的地址"不是同一个。
 *       本类返回的 {@code InetAddress[]} 的用途是交给<b>真正的连接期固定机制</b>（自定义
 *       {@code DnsResolver}/{@code SocketFactory} 之类，让 socket 直接连这些地址），而不是"检查完就当安全"。</li>
 *   <li><b>本类不连 socket，也不保证 DNS 超时</b>：查询耗时、超时、重试全部由注入的 {@link AddressLookup}
 *       负责；本类既不设置超时，也不中断一个已经卡住的查询。</li>
 *   <li><b>不缓存</b>：每次 {@link #resolve(String)} 都调用一次查询实现。调用方若要缓存，必须自己承担
 *       缓存陈旧与"缓存的是已校验结果还是原始结果"的责任；本类返回的数组是副本，调用方改它不会污染
 *       查询实现持有的数组，查询实现之后改写自己的数组也不会影响已返回的数组。</li>
 *   <li>元素本身是 {@link InetAddress}，不可变，因此副本是浅拷贝，共享元素是安全的。</li>
 * </ul>
 *
 * <h2>不承诺什么</h2>
 * <p>校验通过只表示"这一批地址在 {@link PublicAddressPolicy} 的保守策略下都是公网候选"，不表示主机名
 * 存在、地址可达、连接一定成功，也不构成完整 SSRF 防护：重定向逐跳策略、响应体/超时上限、部署侧出口
 * 限制都不在本类范围内。</p>
 */
public final class ValidatedDnsResolver {

    private final AddressLookup addressLookup;

    /**
     * @param addressLookup 受信地址查询实现，<b>不允许为 null</b>：null 会诱使调用方以后补一个默认联网
     *                      实现，因此直接在构造期失败
     */
    public ValidatedDnsResolver(AddressLookup addressLookup) {
        if (addressLookup == null) {
            throw new IllegalArgumentException("受信地址查询不能为空：本类没有默认联网解析实现");
        }
        this.addressLookup = addressLookup;
    }

    /**
     * 校验并返回一次地址查询的结果。
     *
     * @param host 调用方给出的主机名（应当是已通过端点允许列表的 host）；{@code null}/空白会在查询前被拒绝
     * @return 全部通过校验的地址数组，是查询结果的<b>独立副本</b>；数组非空、无 {@code null} 元素
     * @throws DnsResolutionException 主机名语法不通过、查询失败/被中断、结果为空/含 null、或含任一非公网候选地址
     */
    public InetAddress[] resolve(String host) {
        requireAllowedHostSyntax(host);

        InetAddress[] resolved = lookup(host);
        if (resolved == null || resolved.length == 0) {
            throw new DnsResolutionException(Reason.LOOKUP_EMPTY);
        }
        // 先复制再校验：查询实现之后改写自己的数组不会影响我们校验过的内容，返回值也不会指向它的数组。
        InetAddress[] checked = resolved.clone();
        for (InetAddress address : checked) {
            if (address == null) {
                throw new DnsResolutionException(Reason.NULL_ADDRESS);
            }
            if (!PublicAddressPolicy.isPublicCandidate(address)) {
                throw new DnsResolutionException(Reason.NON_PUBLIC_ADDRESS);
            }
        }
        return checked;
    }

    /**
     * 调用受信查询，把受检失败与中断折叠成固定原因码。
     *
     * <p>{@link Error}（含 {@code OutOfMemoryError}、{@code StackOverflowError}、断言错误）不在这里捕获，
     * 也不会被转成业务原因码：那类失败说明进程状态已经不可信，压成一个"查询失败"只会掩盖问题。
     * 中断则要恢复中断标志再抛出，否则线程池里的中断会被吞掉。</p>
     */
    private InetAddress[] lookup(String host) {
        try {
            return addressLookup.lookup(host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DnsResolutionException(Reason.LOOKUP_INTERRUPTED);
        } catch (Exception e) {
            // 刻意不保留 cause，也不把 e.getMessage() 拼进文案：里面通常有主机名/地址/端口。
            throw new DnsResolutionException(Reason.LOOKUP_FAILED);
        }
    }

    private static void requireAllowedHostSyntax(String host) {
        if (host == null || host.isBlank()) {
            throw new DnsResolutionException(Reason.HOST_NOT_ALLOWED);
        }
        try {
            // 复用 E2-A 的既有语法检查（拒绝 IP 字面量、保留名、单标签名、尾点、userinfo 等），
            // 不在这里重写一套 host 语法；这里只借用它的校验，不借用它的允许列表含义。
            new ExternalVideoUrlPolicy.AllowedEndpoint(host, ExternalVideoUrlPolicy.DEFAULT_HTTPS_PORT);
        } catch (IllegalArgumentException e) {
            throw new DnsResolutionException(Reason.HOST_NOT_ALLOWED);
        }
    }

    /**
     * 受信地址查询 seam：{@code host -> 地址数组}。
     *
     * <p>实现方通常是自定义的 {@code DnsResolver}（例如包在 {@code HttpClient} 的解析钩子上），
     * 因此允许抛受检异常：真实的解析失败是 {@code UnknownHostException} 这类受检异常，强迫实现方在内部
     * 转成运行期异常只会把原因码分散到各处。声明 {@code Exception} 而不吞掉中断，是为了让
     * {@link ValidatedDnsResolver} 能区分"失败"与"被中断"。</p>
     */
    @FunctionalInterface
    public interface AddressLookup {

        /**
         * @param host 待解析主机名（本类传入调用方给的原始字符串）
         * @return 该主机名的全部地址；返回 {@code null} 或空数组视为失败
         * @throws Exception 受检失败；{@link InterruptedException} 会被单独识别
         */
        InetAddress[] lookup(String host) throws Exception;
    }
}
