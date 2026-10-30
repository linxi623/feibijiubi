package com.feibijiubi.backend.integration.videosource.transport;

import java.net.InetAddress;
import java.util.List;

/**
 * E2-C1：<b>保守的公网候选地址</b>判断 —— 只看 {@link InetAddress#getAddress()} 返回的原始字节，
 * 不做 DNS 查询、不做反向解析（不调用 {@code getHostName()} / {@code getCanonicalHostName()}）、
 * 不是 Spring bean、不读配置、不打日志，当前只被 {@link ValidatedDnsResolver} 使用。
 *
 * <h2>"候选"两个字是承诺的一部分</h2>
 * <p>本类只回答"这个地址是否属于<b>可以拿去尝试连接的公网候选</b>"，<b>不</b>回答：</p>
 * <ul>
 *   <li>该地址现在真的可达（不探测、不连接、不发包）；</li>
 *   <li>该地址属于某个已知的、完整的 IANA 特殊用途登记表（下面的固定前缀是<b>保守的工程策略</b>，
 *       不是实时同步的注册表，也不随 JDK/IANA 更新而自动变化）；</li>
 *   <li>该地址是 IPv6 全球单播（{@code 2000::/3} 只是"可能是全球单播"的粗粒度上界，本类不细分
 *       其中的子分配，也不宣称其中每个地址都在公网上可达）。</li>
 * </ul>
 * <p>策略方向是<b>宁拒勿放</b>：表里多拒绝一些特殊分配是可以接受的，把非公网地址当公网地址放行不可以。</p>
 *
 * <h2>判据（按字节长度分流）</h2>
 * <ul>
 *   <li>4 字节：按 IPv4 表判断，命中任一拒绝前缀即不是候选，否则是候选。</li>
 *   <li>16 字节：先要求落在 {@code 2000::/3} 内，再拒绝 {@code 2001::/23}（故意比 IANA 的细分更宽，
 *       把这段里的特殊分配一起拒掉）、{@code 2001:db8::/32}、{@code 2002::/16}、{@code 3fff::/20}。
 *       其余 IPv6（未指定 {@code ::}、loopback {@code ::1}、link-local {@code fe80::/10}、
 *       ULA {@code fc00::/7}、组播 {@code ff00::/8}、NAT64 {@code 64:ff9b::/96}、IPv4-mapped 等）
 *       都因为不落在 {@code 2000::/3} 内而被拒绝。</li>
 *   <li>其他长度：直接拒绝（不是合法 IP 地址的字节表示）。</li>
 *   <li>{@code null}：拒绝。</li>
 * </ul>
 *
 * <h2>IPv4-mapped 写法的诚实说明</h2>
 * <p>{@link InetAddress#getByAddress(byte[])} 遇到 {@code ::ffff:a.b.c.d} 这种 16 字节输入时会把它
 * <b>转换成 4 字节的 {@code Inet4Address}</b>，于是本类按 IPv4 表判断（{@code ::ffff:127.0.0.1} 被拒、
 * {@code ::ffff:1.1.1.1} 是候选）。如果 16 字节的 mapped 表示确实以 {@code Inet6Address} 形态到达本类
 * （例如直接构造），它会因为首字节不是 {@code 0x20} 而落在 {@code 2000::/3} 之外，同样被拒绝。
 * 但本类<b>不</b>承诺"mapped 地址的所有表示形式都会得到同一个判定过程"，只承诺上面这两条实际行为。</p>
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li>不用 {@link InetAddress#isSiteLocalAddress()} 之类的 JDK 便捷方法当完整判据：它们不覆盖
 *       CGNAT {@code 100.64/10}、文档网段、基准测试网段、保留段等，只覆盖其中一部分。</li>
 *   <li>不解析字符串形式的 IP（没有自制的点分/十六进制解析器），也不做任何 DNS 解析。</li>
 *   <li>不缓存判定结果：方法本身无状态，硬编码表在类加载时构造且不可变。</li>
 * </ul>
 */
public final class PublicAddressPolicy {

    private static final int IPV4_BYTES = 4;
    private static final int IPV6_BYTES = 16;

    /** IPv4 拒绝前缀：命中即"不是公网候选"。 */
    private static final List<Cidr> REJECTED_IPV4 = List.of(
            v4(8, 0, 0, 0, 0),          // 0.0.0.0/8      "本网络"
            v4(8, 10, 0, 0, 0),         // 10.0.0.0/8     私有
            v4(10, 100, 64, 0, 0),      // 100.64.0.0/10  CGNAT
            v4(8, 127, 0, 0, 0),        // 127.0.0.0/8    loopback
            v4(16, 169, 254, 0, 0),     // 169.254.0.0/16 link-local
            v4(12, 172, 16, 0, 0),      // 172.16.0.0/12  私有
            v4(24, 192, 0, 0, 0),       // 192.0.0.0/24   IETF 协议分配
            v4(24, 192, 0, 2, 0),       // 192.0.2.0/24   TEST-NET-1
            v4(24, 192, 88, 99, 0),     // 192.88.99.0/24 6to4 中继
            v4(16, 192, 168, 0, 0),     // 192.168.0.0/16 私有
            v4(15, 198, 18, 0, 0),      // 198.18.0.0/15  基准测试
            v4(24, 198, 51, 100, 0),    // 198.51.100.0/24 TEST-NET-2
            v4(24, 203, 0, 113, 0),     // 203.0.113.0/24 TEST-NET-3
            v4(4, 224, 0, 0, 0),        // 224.0.0.0/4    组播
            v4(4, 240, 0, 0, 0));       // 240.0.0.0/4    保留（含 255.255.255.255）

    /** IPv6 的"可能是全球单播"上界：{@code 2000::/3}。 */
    private static final Cidr IPV6_GLOBAL_UNICAST_CANDIDATE = v6(3, 0x20);

    /** IPv6 在 {@code 2000::/3} 内部还要拒绝的前缀。 */
    private static final List<Cidr> REJECTED_IPV6 = List.of(
            v6(23, 0x20, 0x01),                 // 2001::/23      故意宽于 IANA 细分，把这段特殊分配一起拒掉
            v6(32, 0x20, 0x01, 0x0d, 0xb8),     // 2001:db8::/32  文档
            v6(16, 0x20, 0x02),                 // 2002::/16      6to4
            v6(20, 0x3f, 0xff));                // 3fff::/20      文档（IPv6 地址格式文档段）

    private PublicAddressPolicy() {
    }

    /**
     * 判断一个地址是否是"可以拿去尝试连接的<b>公网候选</b>"。
     *
     * @param address 待判断地址；{@code null} 返回 {@code false}
     * @return {@code true} 表示不属于下面任何拒绝前缀（IPv6 还要求落在 {@code 2000::/3} 内）；
     *         {@code false} 表示应当拒绝。返回值不代表地址可达，也不代表它是完整的 IANA 公网地址
     */
    public static boolean isPublicCandidate(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (raw.length == IPV4_BYTES) {
            return !anyCovers(REJECTED_IPV4, raw);
        }
        if (raw.length == IPV6_BYTES) {
            if (!IPV6_GLOBAL_UNICAST_CANDIDATE.covers(raw)) {
                return false;
            }
            return !anyCovers(REJECTED_IPV6, raw);
        }
        return false;
    }

    private static boolean anyCovers(List<Cidr> prefixes, byte[] address) {
        for (Cidr prefix : prefixes) {
            if (prefix.covers(address)) {
                return true;
            }
        }
        return false;
    }

    private static Cidr v4(int prefixLength, int b0, int b1, int b2, int b3) {
        return new Cidr(new byte[] {(byte) b0, (byte) b1, (byte) b2, (byte) b3}, prefixLength);
    }

    /**
     * 只写前缀覆盖到的<b>高位字节</b>，其余字节按 {@code 0} 补齐（{@code ::} 的写法）。
     * 例如 {@code v6(20, 0x3f, 0xff)} 就是 {@code 3fff::/20}，{@code v6(3, 0x20)} 就是 {@code 2000::/3}。
     */
    private static Cidr v6(int prefixLength, int... leadingBytes) {
        if (leadingBytes.length > IPV6_BYTES) {
            throw new IllegalArgumentException("IPv6 前缀不能超过 16 字节");
        }
        byte[] network = new byte[IPV6_BYTES];
        for (int i = 0; i < leadingBytes.length; i++) {
            network[i] = (byte) leadingBytes[i];
        }
        return new Cidr(network, prefixLength);
    }

    /**
     * 一个固定前缀。构造时复制字节数组，之后不再被外部修改；比较只按<b>位</b>进行，不做任何
     * 整数类型转换，因此不存在符号位/移位宽度带来的边界错误。
     */
    private record Cidr(byte[] network, int prefixLength) {

        private Cidr {
            if (prefixLength <= 0 || prefixLength > network.length * 8) {
                throw new IllegalArgumentException("前缀长度超出网络字节范围");
            }
            network = network.clone();
        }

        /** @param address 与网络同族的完整地址字节（4 或 16 字节） */
        private boolean covers(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            // byte 提升为 int 时会符号扩展，但和 0x00..0xFF 的掩码按位与之后结果仍是 0..255，不会引入符号位问题。
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
