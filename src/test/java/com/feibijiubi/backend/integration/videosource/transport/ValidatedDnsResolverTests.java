package com.feibijiubi.backend.integration.videosource.transport;

import com.feibijiubi.backend.integration.videosource.transport.DnsResolutionException.Reason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2-C1：{@link ValidatedDnsResolver} 的纯单元测试 —— 不启动 Spring、不联网、不做任何真实 DNS 解析。
 *
 * <p>查询 seam 由测试自己实现（直接返回预先构造的 {@link InetAddress} 数组、抛异常或抛 {@link Error}），
 * 因此这些用例证明的是"校验契约"：整批拒绝、固定原因码、异常脱敏、中断标志恢复、防御性复制、无缓存。
 * 它们<b>不</b>证明真实解析结果、DNS 超时、连接期地址固定，也不构成 SSRF 防护验收。</p>
 */
class ValidatedDnsResolverTests {

    private static final String HOST = "cdn.example.com";

    private final List<String> lookedUpHosts = new ArrayList<>();

    // ---------------------------------------------------------------- 正向

    @Test
    void returnsValidatedPublicAddresses() {
        InetAddress publicV4 = v4(1, 1, 1, 1);
        InetAddress publicV6 = v6(0x2a, 0x00, 0x14, 0x50);
        ValidatedDnsResolver resolver = resolverReturning(new InetAddress[] {publicV4, publicV6});

        InetAddress[] resolved = resolver.resolve(HOST);

        assertEquals(2, resolved.length);
        assertEquals(publicV4, resolved[0]);
        assertEquals(publicV6, resolved[1]);
        assertEquals(List.of(HOST), lookedUpHosts);
    }

    @Test
    void passesOriginalHostToLookupWithoutRewritingIt() {
        ValidatedDnsResolver resolver = resolverReturning(new InetAddress[] {v4(1, 1, 1, 1)});

        resolver.resolve("CDN.Example.COM");
        resolver.resolve("cdn.example.com");

        assertEquals(List.of("CDN.Example.COM", "cdn.example.com"), lookedUpHosts);
    }

    // ---------------------------------------------------------------- 整批拒绝

    @Test
    void rejectsWholeBatchWhenAnyAddressIsNotPublic() {
        assertReason(Reason.NON_PUBLIC_ADDRESS, resolverReturning(new InetAddress[] {v4(1, 1, 1, 1), v4(10, 0, 0, 1)}));
        assertReason(Reason.NON_PUBLIC_ADDRESS, resolverReturning(new InetAddress[] {v4(10, 0, 0, 1), v4(1, 1, 1, 1)}));
        assertReason(Reason.NON_PUBLIC_ADDRESS, resolverReturning(new InetAddress[] {v4(1, 1, 1, 1), v4(169, 254, 0, 1)}));
        assertReason(Reason.NON_PUBLIC_ADDRESS,
                resolverReturning(new InetAddress[] {v6(0x2a, 0x00, 0x14, 0x50), v6(0x20, 0x01, 0x0d, 0xb8)}));
    }

    @Test
    void rejectsNullLookupResult() {
        assertReason(Reason.LOOKUP_EMPTY, resolverReturning(null));
    }

    @Test
    void rejectsEmptyLookupResult() {
        assertReason(Reason.LOOKUP_EMPTY, resolverReturning(new InetAddress[0]));
    }

    @Test
    void rejectsLookupResultContainingNullEvenAlongsidePublicAddresses() {
        assertReason(Reason.NULL_ADDRESS, resolverReturning(new InetAddress[] {v4(1, 1, 1, 1), null}));
        assertReason(Reason.NULL_ADDRESS, resolverReturning(new InetAddress[] {null, v4(1, 1, 1, 1)}));
    }

    // ---------------------------------------------------------------- 主机名语法先于查询

    @Test
    void rejectsHostsThatFailUrlSyntaxPolicyBeforeAnyLookup() {
        List<String> rejectedHosts = List.of(
                "127.0.0.1", "10.0.0.1", "0x7f.0.0.1", "::1", "[2001:db8::1]",
                "localhost", "intranet", "cdn.local", "cdn.example.com.",
                "http://cdn.example.com", "cdn.example.com/api", "user@cdn.example.com",
                " ", "");
        ValidatedDnsResolver resolver = resolverReturning(new InetAddress[] {v4(1, 1, 1, 1)});

        for (String rejectedHost : rejectedHosts) {
            assertReason(Reason.HOST_NOT_ALLOWED, resolver, rejectedHost);
        }
        assertReason(Reason.HOST_NOT_ALLOWED, resolver, null);
        assertTrue(lookedUpHosts.isEmpty(), "语法不通过的主机名不应该触发任何查询");

        // 正向对照：证明上面的空列表不是因为 seam 根本没被接上。
        resolver.resolve(HOST);
        assertEquals(List.of(HOST), lookedUpHosts);
    }

    // ---------------------------------------------------------------- 异常脱敏与中断/Error 边界

    @Test
    void sanitisesCheckedLookupFailure() {
        IOException failure = new IOException("lookup failed for " + HOST + " at 10.0.0.1:443");
        ValidatedDnsResolver resolver = new ValidatedDnsResolver(host -> {
            lookedUpHosts.add(host);
            throw failure;
        });

        DnsResolutionException thrown = assertThrows(DnsResolutionException.class, () -> resolver.resolve(HOST));

        assertEquals(Reason.LOOKUP_FAILED, thrown.reason());
        assertEquals(Reason.LOOKUP_FAILED.description(), thrown.getMessage());
        assertNull(thrown.getCause(), "原始异常不能作为 cause 透出");
        assertFalse(thrown.getMessage().contains(HOST), "文案不能含主机名");
        assertFalse(thrown.getMessage().contains("10.0.0.1"), "文案不能含地址");
        assertEquals(List.of(HOST), lookedUpHosts);
    }

    @Test
    void restoresInterruptFlagWhenLookupIsInterrupted() {
        ValidatedDnsResolver resolver = new ValidatedDnsResolver(host -> {
            throw new InterruptedException("interrupted by test");
        });

        try {
            DnsResolutionException thrown =
                    assertThrows(DnsResolutionException.class, () -> resolver.resolve(HOST));
            assertEquals(Reason.LOOKUP_INTERRUPTED, thrown.reason());
            assertNull(thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted(), "中断标志必须被重新设置");
        } finally {
            // 清掉标志，避免影响同一线程上的其他用例。
            Thread.interrupted();
        }
    }

    @Test
    void doesNotSwallowErrors() {
        Error failure = new Error("simulated JVM-level failure");
        ValidatedDnsResolver resolver = new ValidatedDnsResolver(host -> {
            throw failure;
        });

        Error thrown = assertThrows(Error.class, () -> resolver.resolve(HOST));

        assertSame(failure, thrown);
    }

    // ---------------------------------------------------------------- 副本与缓存

    @Test
    void returnedArrayIsIndependentOfTheLookupArrayAndNotCached() {
        InetAddress[] source = {v4(1, 1, 1, 1), v4(8, 8, 8, 8)};
        ValidatedDnsResolver resolver = resolverReturning(source);

        InetAddress[] first = resolver.resolve(HOST);
        assertEquals(2, first.length);

        // 查询实现之后改写自己的数组：已返回的结果不受影响。
        source[0] = v4(9, 9, 9, 9);
        assertEquals(v4(1, 1, 1, 1), first[0]);

        // 调用方改写返回值：下一次结果不受影响，且查询被再次调用（本类不缓存）。
        first[1] = v4(192, 168, 0, 1);
        InetAddress[] second = resolver.resolve(HOST);
        assertNotSame(first, second);
        assertEquals(v4(9, 9, 9, 9), second[0]);
        assertEquals(v4(8, 8, 8, 8), second[1]);
        assertEquals(List.of(HOST, HOST), lookedUpHosts);
    }

    @Test
    void rejectsNullLookupAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ValidatedDnsResolver(null));
    }

    // ---------------------------------------------------------------- 测试专属 helper

    private ValidatedDnsResolver resolverReturning(InetAddress[] result) {
        return new ValidatedDnsResolver(host -> {
            lookedUpHosts.add(host);
            return result;
        });
    }

    private static void assertReason(Reason expected, ValidatedDnsResolver resolver) {
        assertReason(expected, resolver, HOST);
    }

    private static void assertReason(Reason expected, ValidatedDnsResolver resolver, String host) {
        DnsResolutionException thrown =
                assertThrows(DnsResolutionException.class, () -> resolver.resolve(host));
        assertEquals(expected, thrown.reason());
        assertEquals(expected.description(), thrown.getMessage());
    }

    private static InetAddress v4(int b0, int b1, int b2, int b3) {
        return address(new byte[] {(byte) b0, (byte) b1, (byte) b2, (byte) b3});
    }

    /** 只写高位字节，其余补 0；例如 {@code v6(0x2a, 0x00)} 就是 {@code 2a00::}。 */
    private static InetAddress v6(int... leadingBytes) {
        byte[] raw = new byte[16];
        for (int i = 0; i < leadingBytes.length; i++) {
            raw[i] = (byte) leadingBytes[i];
        }
        return address(raw);
    }

    private static InetAddress address(byte[] raw) {
        try {
            return InetAddress.getByAddress(raw);
        } catch (UnknownHostException e) {
            throw new AssertionError("测试地址字节长度不合法", e);
        }
    }
}
