package com.feibijiubi.backend.videosource.integration.transport;

import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2-C1：{@link PublicAddressPolicy} 的纯单元测试 —— 不启动 Spring、不联网、不做任何 DNS 解析
 * （所有地址都用 {@link InetAddress#getByAddress(byte[])} 从字节构造，从不调用 {@code getByName}）。
 *
 * <p>断言的边界与实现一致：这里证明的是"哪些字节地址会被当成公网候选、哪些会被拒绝、边界两侧分别是哪一边"，
 * <b>不</b>证明某个真实公网地址此刻可达，也不构成"策略等于完整 IANA 特殊用途登记表"的验收。</p>
 */
class PublicAddressPolicyTests {

    // ---------------------------------------------------------------- 正向：真实公网候选

    @Test
    void acceptsRealPublicIpv4Candidates() {
        assertCandidate(
                v4(1, 1, 1, 1),
                v4(8, 8, 8, 8),
                v4(93, 184, 216, 34),
                v4(202, 96, 128, 86));
    }

    @Test
    void acceptsIpv6InsideGlobalUnicastCandidateBlockAndRejectsOutsideIt() {
        assertCandidate(
                v6(0x20, 0x00),                     // 2000::   2000::/3 的下边界
                v6(0x2a, 0x00, 0x14, 0x50),         // 2a00:1450::
                v6(0x26, 0x06, 0x47, 0x00));        // 2606:4700::

        assertRejected(
                v6(0x1f, 0xff),                     // 1fff::   2000::/3 之外（紧邻下边界）
                v6(0x40, 0x00),                     // 4000::   2000::/3 之外（紧邻上边界）
                v6(0x3f, 0xff));                    // 3fff::   在 /3 内但被 3fff::/20 拒绝
    }

    // ---------------------------------------------------------------- 反向：IPv4 每个网段与相邻边界

    @Test
    void rejectsIpv4ZeroPrivateAndLoopbackRangesWithTheirNeighbours() {
        assertRejected(
                v4(0, 0, 0, 0), v4(0, 255, 255, 255),
                v4(10, 0, 0, 0), v4(10, 255, 255, 255),
                v4(127, 0, 0, 1), v4(127, 255, 255, 255),
                v4(172, 16, 0, 0), v4(172, 31, 255, 255),
                v4(192, 168, 0, 0), v4(192, 168, 255, 255));

        assertCandidate(
                v4(1, 0, 0, 0),                     // 0/8 之外
                v4(9, 255, 255, 255), v4(11, 0, 0, 0),
                v4(126, 255, 255, 255), v4(128, 0, 0, 0),
                v4(172, 15, 255, 255), v4(172, 32, 0, 0),
                v4(192, 167, 255, 255), v4(192, 169, 0, 0));
    }

    @Test
    void rejectsIpv4CgnatLinkLocalAndBenchmarkRangesWithTheirNeighbours() {
        assertRejected(
                v4(100, 64, 0, 0), v4(100, 127, 255, 255),
                v4(169, 254, 0, 1), v4(169, 254, 255, 255),
                v4(198, 18, 0, 0), v4(198, 19, 255, 255));

        assertCandidate(
                v4(100, 63, 255, 255), v4(100, 128, 0, 0),
                v4(169, 253, 255, 255), v4(169, 255, 0, 0),
                v4(198, 17, 255, 255), v4(198, 20, 0, 0));
    }

    @Test
    void rejectsIpv4DocumentationAndProtocolAssignmentRangesWithTheirNeighbours() {
        assertRejected(
                v4(192, 0, 0, 9), v4(192, 0, 0, 255),
                v4(192, 0, 2, 1), v4(192, 0, 2, 255),
                v4(192, 88, 99, 1),
                v4(198, 51, 100, 7),
                v4(203, 0, 113, 9));

        assertCandidate(
                v4(191, 255, 255, 255), v4(192, 0, 1, 1),
                v4(192, 0, 3, 1),
                v4(192, 88, 98, 255), v4(192, 88, 100, 0),
                v4(198, 51, 99, 255), v4(198, 51, 101, 0),
                v4(203, 0, 112, 255), v4(203, 0, 114, 0));
    }

    @Test
    void rejectsIpv4MulticastAndReservedRangesWithTheirNeighbours() {
        assertRejected(
                v4(224, 0, 0, 0), v4(239, 255, 255, 255),
                v4(240, 0, 0, 0), v4(255, 255, 255, 255));

        assertCandidate(v4(223, 255, 255, 255));
    }

    // ---------------------------------------------------------------- 反向：IPv6 特殊分配

    @Test
    void rejectsIpv6SpecialAssignmentsInsideGlobalUnicastBlock() {
        assertRejected(
                v6(0x20, 0x01),                     // 2001::       2001::/23 起点
                v6(0x20, 0x01, 0x01, 0x00),         // 2001:100::   2001::/23 内部
                v6(0x20, 0x01, 0x01, 0xff),         // 2001:1ff::   2001::/23 末端
                v6(0x20, 0x01, 0x0d, 0xb8),         // 2001:db8::   文档
                v6(0x20, 0x02),                     // 2002::       6to4
                v6(0x20, 0x02, 0xff, 0xff),         // 2002:ffff::
                v6(0x3f, 0xff, 0x00, 0x00),         // 3fff::       3fff::/20 起点
                v6(0x3f, 0xff, 0x0f, 0xff));        // 3fff:fff::   3fff::/20 末端

        assertCandidate(
                v6(0x20, 0x01, 0x02, 0x00),         // 2001:200::   2001::/23 之外（紧邻末端）
                v6(0x20, 0x01, 0x0d, 0xb7),         // 2001:db7::   2001:db8::/32 之外（紧邻下边界）
                v6(0x20, 0x01, 0x0d, 0xb9),         // 2001:db9::   2001:db8::/32 之外（紧邻上边界）
                v6(0x20, 0x03),                     // 2003::       2002::/16 之外
                v6(0x3f, 0xfe, 0xff, 0xff),         // 3ffe:ffff::  3fff::/20 之外（紧邻下边界）
                v6(0x3f, 0xff, 0x10, 0x00));        // 3fff:1000::  3fff::/20 之外（紧邻上边界）
    }

    @Test
    void rejectsIpv6NonGlobalFamilies() {
        assertRejected(
                v6(),                                                   // ::        未指定
                v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),      // ::1       loopback
                v6(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), // fe80::1   link-local
                v6(0xfc, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), // fc00::1   ULA
                v6(0xfd, 0x12, 0x34, 0x56),                             // fd12:3456:: ULA
                v6(0xff, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), // ff02::1   组播
                v6(0x00, 0x64, 0xff, 0x9b));                            // 64:ff9b::  NAT64
    }

    // ---------------------------------------------------------------- IPv4-mapped 的诚实边界

    /**
     * {@link InetAddress#getByAddress(byte[])} 遇到 16 字节的 {@code ::ffff:a.b.c.d} 会把它转换成 4 字节的
     * {@link Inet4Address}，所以本策略对这类输入<b>按 IPv4 表</b>判断。
     */
    @Test
    void treatsIpv4MappedSixteenByteInputAsIpv4BecauseGetByAddressConvertsIt() {
        InetAddress mappedLoopback = address(mappedIpv4Bytes(127, 0, 0, 1));
        assertTrue(mappedLoopback instanceof Inet4Address);
        assertFalse(PublicAddressPolicy.isPublicCandidate(mappedLoopback));

        InetAddress mappedPublic = address(mappedIpv4Bytes(1, 1, 1, 1));
        assertTrue(mappedPublic instanceof Inet4Address);
        assertTrue(PublicAddressPolicy.isPublicCandidate(mappedPublic));
    }

    /**
     * 如果 16 字节的 mapped 表示确实以 {@link Inet6Address} 形态到达（这里用
     * {@link Inet6Address#getByAddress(String, byte[], int)} 直接构造，它<b>不做</b> mapped 转换），
     * 它因为首字节不是 {@code 0x20} 而落在 {@code 2000::/3} 之外，同样被拒绝。
     *
     * <p>注意这<b>不</b>等于"mapped 地址的所有表示形式都走同一条判断路径"：上一条测试证明 4 字节路径
     * 按 IPv4 判，本条证明 16 字节路径按 IPv6 判，两条路径的判定过程不同。</p>
     */
    @Test
    void rejectsIpv4MappedBytesThatStaySixteenByteInet6Address() {
        InetAddress asInet6 = address6(mappedIpv4Bytes(1, 1, 1, 1));
        assertTrue(asInet6 instanceof Inet6Address);
        assertFalse(PublicAddressPolicy.isPublicCandidate(asInet6));

        assertFalse(PublicAddressPolicy.isPublicCandidate(address6(mappedIpv4Bytes(10, 0, 0, 1))));
    }

    @Test
    void rejectsNullAddress() {
        assertFalse(PublicAddressPolicy.isPublicCandidate(null));
    }

    // ---------------------------------------------------------------- 测试专属的字节 helper（不是 IP 字符串解析器）

    private static void assertRejected(InetAddress... addresses) {
        for (InetAddress address : addresses) {
            assertFalse(PublicAddressPolicy.isPublicCandidate(address), "应当拒绝该地址");
        }
    }

    private static void assertCandidate(InetAddress... addresses) {
        for (InetAddress address : addresses) {
            assertTrue(PublicAddressPolicy.isPublicCandidate(address), "应当是公网候选");
        }
    }

    private static InetAddress v4(int b0, int b1, int b2, int b3) {
        return address(new byte[] {(byte) b0, (byte) b1, (byte) b2, (byte) b3});
    }

    /** 只写高位字节，其余补 0；例如 {@code v6(0x20, 0x01)} 就是 {@code 2001::}。 */
    private static InetAddress v6(int... leadingBytes) {
        byte[] raw = new byte[16];
        for (int i = 0; i < leadingBytes.length; i++) {
            raw[i] = (byte) leadingBytes[i];
        }
        return address(raw);
    }

    /** {@code ::ffff:a.b.c.d} 的 16 字节表示（由 JDK 决定是否转换成 Inet4Address）。 */
    private static byte[] mappedIpv4Bytes(int b0, int b1, int b2, int b3) {
        byte[] raw = new byte[16];
        raw[10] = (byte) 0xff;
        raw[11] = (byte) 0xff;
        raw[12] = (byte) b0;
        raw[13] = (byte) b1;
        raw[14] = (byte) b2;
        raw[15] = (byte) b3;
        return raw;
    }

    private static InetAddress address(byte[] raw) {
        try {
            return InetAddress.getByAddress(raw);
        } catch (UnknownHostException e) {
            throw new AssertionError("测试地址字节长度不合法", e);
        }
    }

    private static InetAddress address6(byte[] raw) {
        try {
            return Inet6Address.getByAddress(null, raw, -1);
        } catch (UnknownHostException e) {
            throw new AssertionError("测试地址字节长度不合法", e);
        }
    }
}
