package com.feibijiubi.backend.videosource.integration;

import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.AllowedEndpoint;
import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectedUrlException;
import com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.AUTHORITY_INVALID;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.EMPTY_URL;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.ENCODED_CONTROL_CHARACTER;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.ENDPOINT_NOT_ALLOWED;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.FRAGMENT_PRESENT;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.HOST_ILLEGAL;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.HOST_IS_IP_LITERAL;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.HOST_IS_SPECIAL_NAME;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.ILLEGAL_CHARACTER;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.MALFORMED_URL;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.MISSING_HOST;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.PORT_INVALID;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.SCHEME_NOT_HTTPS;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.URL_TOO_LONG;
import static com.feibijiubi.backend.videosource.integration.ExternalVideoUrlPolicy.RejectionReason.USERINFO_PRESENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2-A：{@link ExternalVideoUrlPolicy} 的纯单元测试 —— 不启动 Spring、不联网、不做 DNS、不连任何库。
 *
 * <p>断言的边界与实现一致：这里证明的是"哪些字符串会被接受/拒绝、拒绝时给出哪个固定原因码、拒绝信息里
 * 不出现原始 URL"，<b>不</b>证明允许列表里的域名真的解析到公网地址，也不构成完整 SSRF 防护的验收。</p>
 */
class ExternalVideoUrlPolicyTests {

    private static final int MAX_LENGTH = 2048;

    private static final ExternalVideoUrlPolicy POLICY = new ExternalVideoUrlPolicy(
            List.of(
                    new AllowedEndpoint("cdn.example.com", 443),
                    new AllowedEndpoint("hls.example.com", 443),
                    new AllowedEndpoint("alt.example.com", 8443)),
            MAX_LENGTH);

    private static ExternalVideoUrlPolicy policy(AllowedEndpoint... endpoints) {
        return new ExternalVideoUrlPolicy(List.of(endpoints), MAX_LENGTH);
    }

    // ---------------------------------------------------------------- 正向：语法、格式、保真

    @Test
    void acceptsMp4AndHlsFormsWithoutJudgingFormatByExtension() {
        List<String> accepted = List.of(
                "https://cdn.example.com/video/abc.mp4",
                "https://cdn.example.com/hls/index.m3u8",
                "https://hls.example.com/2026/01/01/playlist.m3u8?token=abc",
                "https://cdn.example.com/segments/seg-00001.ts",
                "https://cdn.example.com/play/12345",
                "https://cdn.example.com",
                "https://alt.example.com:8443/live/stream");

        for (String raw : accepted) {
            URI uri = POLICY.validate(raw);
            assertEquals(raw, uri.toString(), "校验通过后必须原样返回，不改写、不重新编码");
        }
    }

    @Test
    void schemeComparisonIsCaseInsensitiveAndResultKeepsOriginalSpelling() {
        URI uri = POLICY.validate("HTTPS://cdn.example.com/a.mp4");

        assertEquals("https", uri.getScheme().toLowerCase(java.util.Locale.ROOT));
        assertEquals("HTTPS://cdn.example.com/a.mp4", uri.toString());
    }

    @Test
    void hostComparisonIsCaseInsensitive() {
        ExternalVideoUrlPolicy lower = policy(new AllowedEndpoint("cdn.example.com", 443));

        assertEquals("https://CDN.Example.COM/a.mp4",
                lower.validate("https://CDN.Example.COM/a.mp4").toString());
        assertEquals("cdn.example.com", new AllowedEndpoint("CDN.Example.COM", 443).host(),
                "允许端点的 host 在构造时就规范化成小写");
    }

    @Test
    void absentPortEqualsExplicit443AndBothNeedA443Entry() {
        ExternalVideoUrlPolicy only443 = policy(new AllowedEndpoint("cdn.example.com", 443));

        assertNotNull(only443.validate("https://cdn.example.com/a.mp4"));
        assertNotNull(only443.validate("https://cdn.example.com:443/a.mp4"));
        assertEquals(ENDPOINT_NOT_ALLOWED,
                assertRejected(only443, "https://cdn.example.com:8443/a.mp4"));
    }

    @Test
    void nonDefaultPortNeedsItsOwnEntry() {
        ExternalVideoUrlPolicy only8443 = policy(new AllowedEndpoint("cdn.example.com", 8443));

        assertNotNull(only8443.validate("https://cdn.example.com:8443/a.mp4"));
        assertEquals(ENDPOINT_NOT_ALLOWED,
                assertRejected(only8443, "https://cdn.example.com/a.mp4"),
                "缺省端口按 443 处理，没有 443 条目就必须拒绝");
    }

    @Test
    void signedQueryAndPercentEncodingArePreservedVerbatim() {
        String raw = "https://cdn.example.com/hls/index.m3u8"
                + "?b=2&a=1&sig=AbC%2FdEf%3D&expires=1700000000&name=a%20b&plus=a+b";

        URI uri = POLICY.validate(raw);

        assertEquals(raw, uri.toString());
        assertEquals("b=2&a=1&sig=AbC%2FdEf%3D&expires=1700000000&name=a%20b&plus=a+b",
                uri.getRawQuery(),
                "签名 query 的参数顺序与百分号编码必须原样保留，不解码、不排序、不重新编码");
    }

    // ---------------------------------------------------------------- 拒绝：scheme

    @Test
    void rejectsEverythingButHttps() {
        assertEquals(SCHEME_NOT_HTTPS, assertRejected(POLICY, "http://cdn.example.com/a.mp4"));
        assertEquals(SCHEME_NOT_HTTPS, assertRejected(POLICY, "ftp://cdn.example.com/a.mp4"));
        assertEquals(SCHEME_NOT_HTTPS, assertRejected(POLICY, "javascript:alert(1)"));
        assertEquals(SCHEME_NOT_HTTPS, assertRejected(POLICY, "data:text/plain,hello"));
        assertEquals(SCHEME_NOT_HTTPS, assertRejected(POLICY, "//cdn.example.com/a.mp4"),
                "协议相对写法没有 scheme，必须拒绝");
        assertEquals(SCHEME_NOT_HTTPS, assertRejected(POLICY, "cdn.example.com/a.mp4"));
    }

    // ---------------------------------------------------------------- 拒绝：字符与控制字符

    @Test
    void rejectsWhitespaceControlCharactersAndBackslash() {
        assertEquals(EMPTY_URL, assertRejected(POLICY, null));
        assertEquals(EMPTY_URL, assertRejected(POLICY, ""));
        assertEquals(EMPTY_URL, assertRejected(POLICY, "   "));
        assertEquals(ILLEGAL_CHARACTER, assertRejected(POLICY, "https://cdn.example.com/a b.mp4"));
        assertEquals(ILLEGAL_CHARACTER, assertRejected(POLICY, " https://cdn.example.com/a.mp4"));
        assertEquals(ILLEGAL_CHARACTER,
                assertRejected(POLICY, "https://cdn.example.com/a.mp4\r\nHost: evil.test"));
        assertEquals(ILLEGAL_CHARACTER,
                assertRejected(POLICY, "https://cdn.example.com/a.mp4\tx"));
        assertEquals(ILLEGAL_CHARACTER,
                assertRejected(POLICY, "https://cdn.example.com\\@evil.test/a.mp4"),
                "反斜杠在浏览器里等价于 /，必须拒绝而不是交给下游解释");
        assertEquals(ILLEGAL_CHARACTER, assertRejected(POLICY, "https://cdn.example.com/视频.mp4"),
                "非 ASCII 必须先由调用方转义/punycode，本类不改写也不猜");
    }

    @Test
    void rejectsPercentEncodedControlCharactersWithoutDecodingAnything() {
        assertEquals(ENCODED_CONTROL_CHARACTER,
                assertRejected(POLICY, "https://cdn.example.com/a%00b.mp4"));
        assertEquals(ENCODED_CONTROL_CHARACTER,
                assertRejected(POLICY, "https://cdn.example.com/a%0d%0aSet-Cookie:x=1"));
        assertEquals(ENCODED_CONTROL_CHARACTER,
                assertRejected(POLICY, "https://cdn.example.com/a%0A.mp4"));
        assertEquals(ENCODED_CONTROL_CHARACTER,
                assertRejected(POLICY, "https://cdn.example.com/a%1f.mp4"));
        assertEquals(ENCODED_CONTROL_CHARACTER,
                assertRejected(POLICY, "https://cdn.example.com/a%7F.mp4"));
        assertNotNull(POLICY.validate("https://cdn.example.com/a%20b.mp4"),
                "编码后的空格是签名 query 的正常写法，不在拒绝范围");
    }

    @Test
    void rejectsIllegalEscapesAndMalformedSyntax() {
        assertEquals(MALFORMED_URL, assertRejected(POLICY, "https://cdn.example.com/a%zz.mp4"));
        assertEquals(MALFORMED_URL, assertRejected(POLICY, "https://cdn.example.com/a.mp4?x=%"));
        assertEquals(MALFORMED_URL, assertRejected(POLICY, "https://cdn.example.com/<script>.mp4"));
    }

    // ---------------------------------------------------------------- 拒绝：authority / host

    @Test
    void rejectsMissingHostUserInfoAndFragment() {
        assertEquals(MISSING_HOST, assertRejected(POLICY, "https:///a.mp4"));
        assertEquals(USERINFO_PRESENT,
                assertRejected(POLICY, "https://user:pass@cdn.example.com/a.mp4"));
        assertEquals(USERINFO_PRESENT,
                assertRejected(POLICY, "https://user@cdn.example.com/a.mp4"));
        assertEquals(FRAGMENT_PRESENT, assertRejected(POLICY, "https://cdn.example.com/a.mp4#t=10"));
        assertEquals(FRAGMENT_PRESENT, assertRejected(POLICY, "https://cdn.example.com/a.mp4#"));
    }

    @Test
    void rejectsNonCanonicalAuthority() {
        assertEquals(AUTHORITY_INVALID, assertRejected(POLICY, "https://cdn.example.com:0443/a.mp4"),
                "前导零端口不会被 URI 当成另一个端口，这里也不接受这种写法");
        assertEquals(AUTHORITY_INVALID, assertRejected(POLICY, "https://cdn_example.com/a.mp4"),
                "host 含下划线时 URI 认为它不是合法 host，必须拒绝而不是猜");
    }

    @Test
    void rejectsIpLiteralAndItsShorthandVariants() {
        assertEquals(HOST_IS_IP_LITERAL, assertRejected(POLICY, "https://127.0.0.1/a.mp4"));
        assertEquals(HOST_IS_IP_LITERAL, assertRejected(POLICY, "https://10.0.0.1/a.mp4"));
        // URI 可能在 host 分类之前就拒绝缩写 authority；安全契约是拒绝，
        // 不为统一细分原因码而另写一套宽松 authority 解析器。
        for (String host : new String[]{"127.1", "0177.0.0.1", "2130706433",
                "0x7f000001", "0x7f.0.0.1"}) {
            var reason = assertRejected(POLICY, "https://" + host + "/a.mp4");
            assertTrue(reason == HOST_IS_IP_LITERAL || reason == AUTHORITY_INVALID,
                    "IP 缩写必须在 authority 或 host 校验阶段被拒绝");
        }
        assertEquals(HOST_IS_IP_LITERAL, assertRejected(POLICY, "https://[::1]/a.mp4"));
        assertEquals(HOST_IS_IP_LITERAL,
                assertRejected(POLICY, "https://[::ffff:127.0.0.1]/a.mp4"));
    }

    @Test
    void rejectsLocalAndInternalNames() {
        assertEquals(HOST_IS_SPECIAL_NAME, assertRejected(POLICY, "https://localhost/a.mp4"));
        assertEquals(HOST_IS_SPECIAL_NAME,
                assertRejected(POLICY, "https://localhost.localdomain/a.mp4"));
        assertEquals(HOST_IS_SPECIAL_NAME, assertRejected(POLICY, "https://intranet/a.mp4"));
        assertEquals(HOST_IS_SPECIAL_NAME, assertRejected(POLICY, "https://nas.local/a.mp4"));
        assertEquals(HOST_IS_SPECIAL_NAME, assertRejected(POLICY, "https://svc.internal/a.mp4"));
        assertEquals(HOST_ILLEGAL, assertRejected(POLICY, "https://myhost/a.mp4"),
                "单 label 名称不是可白名单的公网域名");
        assertEquals(HOST_ILLEGAL, assertRejected(POLICY, "https://cdn.example.com./a.mp4"),
                "尾点域名必须拒绝，不做规范化");
    }

    @Test
    void rejectsPortsOutsideRange() {
        assertEquals(PORT_INVALID, assertRejected(POLICY, "https://cdn.example.com:0/a.mp4"));
        assertEquals(PORT_INVALID, assertRejected(POLICY, "https://cdn.example.com:99999/a.mp4"));
    }

    // ---------------------------------------------------------------- 拒绝：允许列表

    @Test
    void allowlistMatchIsExactAndNeverSuffixOrWildcard() {
        assertEquals(ENDPOINT_NOT_ALLOWED,
                assertRejected(POLICY, "https://cdn.example.com.evil.test/a.mp4"));
        assertEquals(ENDPOINT_NOT_ALLOWED,
                assertRejected(POLICY, "https://sub.cdn.example.com/a.mp4"),
                "不允许自动放行子域");
        assertEquals(ENDPOINT_NOT_ALLOWED,
                assertRejected(POLICY, "https://cdn.example.co/a.mp4"));
        assertEquals(ENDPOINT_NOT_ALLOWED,
                assertRejected(POLICY, "https://evil.test/?u=https://cdn.example.com/a.mp4"),
                "允许端点只来自受信配置，不来自待校验 URL 里的字符串");
        assertEquals(ENDPOINT_NOT_ALLOWED,
                assertRejected(POLICY, "https://cdn.example.com:4430/a.mp4"),
                "端口也要全等匹配，4430 不是 443");
    }

    @Test
    void rejectsUrlLongerThanConfiguredMaximum() {
        ExternalVideoUrlPolicy small = new ExternalVideoUrlPolicy(
                List.of(new AllowedEndpoint("cdn.example.com", 443)), 64);
        String base = "https://cdn.example.com/";
        String atLimit = base + "a".repeat(64 - base.length());

        assertEquals(64, atLimit.length());
        assertNotNull(small.validate(atLimit), "恰好等于上限必须接受");
        assertEquals(URL_TOO_LONG, assertRejected(small, atLimit + "a"));
    }

    // ---------------------------------------------------------------- 拒绝信息与允许列表不变性

    @Test
    void rejectionMessageNeverLeaksTheUrlAndCarriesNoCause() {
        String raw = "https://cdn.example.com:8443/private/secret.mp4?sig=TOPSECRETVALUE";
        RejectedUrlException rejected = assertThrows(RejectedUrlException.class,
                () -> POLICY.validate(raw));

        assertEquals(ENDPOINT_NOT_ALLOWED, rejected.reason());
        assertEquals(rejected.reason().description(), rejected.getMessage(),
                "message 必须是固定文案，而不是拼接输入");
        assertNull(rejected.getCause(), "拒绝异常不允许携带 cause");
        assertFalse(rejected.getMessage().contains("cdn.example.com"));
        assertFalse(rejected.getMessage().contains("TOPSECRETVALUE"));
        assertFalse(rejected.getMessage().contains("secret"));
        assertFalse(rejected.getMessage().contains("8443"));
        assertFalse(rejected.getMessage().contains("https"));
    }

    @Test
    void malformedUrlRejectionDropsTheUrisyntaxExceptionCause() {
        String raw = "https://cdn.example.com/a%zz.mp4?sig=TOPSECRETVALUE";
        RejectedUrlException rejected = assertThrows(RejectedUrlException.class,
                () -> POLICY.validate(raw));

        assertEquals(MALFORMED_URL, rejected.reason());
        assertNull(rejected.getCause(),
                "URISyntaxException 的 message 会拼进原始 URL，因此不能作为 cause 传出去");
        assertFalse(rejected.getMessage().contains("TOPSECRETVALUE"));
    }

    @Test
    void allowlistIsDefensivelyCopiedAndUnmodifiable() {
        List<AllowedEndpoint> mutable = new ArrayList<>();
        mutable.add(new AllowedEndpoint("cdn.example.com", 443));
        ExternalVideoUrlPolicy policy = new ExternalVideoUrlPolicy(mutable, MAX_LENGTH);

        mutable.add(new AllowedEndpoint("evil.test", 443));

        assertEquals(Set.of(new AllowedEndpoint("cdn.example.com", 443)), policy.allowedEndpoints());
        assertEquals(ENDPOINT_NOT_ALLOWED, assertRejected(policy, "https://evil.test/a.mp4"),
                "构造后再改调用方的集合不能影响策略");
        assertThrows(UnsupportedOperationException.class,
                () -> policy.allowedEndpoints().add(new AllowedEndpoint("evil.test", 443)));
    }

    @Test
    void emptyOrMissingAllowlistFailsClosedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalVideoUrlPolicy(List.of(), MAX_LENGTH));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalVideoUrlPolicy(null, MAX_LENGTH));
        assertThrows(IllegalArgumentException.class,
                () -> new ExternalVideoUrlPolicy(
                        List.of(new AllowedEndpoint("cdn.example.com", 443)), 0));
    }

    @Test
    void allowedEndpointValidatesItsOwnTrustedConfiguration() {
        assertEquals(new AllowedEndpoint("cdn.example.com", 443),
                new AllowedEndpoint("CDN.Example.COM", 443));

        assertThrows(IllegalArgumentException.class, () -> new AllowedEndpoint(null, 443));
        assertThrows(IllegalArgumentException.class, () -> new AllowedEndpoint("127.0.0.1", 443));
        assertThrows(IllegalArgumentException.class, () -> new AllowedEndpoint("[::1]", 443));
        assertThrows(IllegalArgumentException.class, () -> new AllowedEndpoint("localhost", 443));
        assertThrows(IllegalArgumentException.class,
                () -> new AllowedEndpoint("cdn.example.com.", 443));
        assertThrows(IllegalArgumentException.class, () -> new AllowedEndpoint("myhost", 443));
        assertThrows(IllegalArgumentException.class,
                () -> new AllowedEndpoint("cdn.example.com", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AllowedEndpoint("cdn.example.com", 65536));
    }

    @Test
    void internationalDomainMustBePunycodeBeforeValidation() {
        assertEquals(ILLEGAL_CHARACTER, assertRejected(POLICY, "https://例子.example.com/a.mp4"),
                "本类不替你转 punycode，也不猜");

        ExternalVideoUrlPolicy idn = policy(new AllowedEndpoint("xn--fsqu00a.example.com", 443));
        assertNotNull(idn.validate("https://xn--fsqu00a.example.com/a.mp4"),
                "转成 punycode 后可以按普通 ASCII 域名处理");
    }

    /**
     * 统一的拒绝断言：原因码由调用方比对，这里额外固定"固定文案 + 无 cause"两条不变量。
     */
    private static RejectionReason assertRejected(ExternalVideoUrlPolicy policy, String rawUrl) {
        RejectedUrlException rejected = assertThrows(RejectedUrlException.class,
                () -> policy.validate(rawUrl));
        assertNull(rejected.getCause(), "拒绝异常不允许携带 cause");
        assertEquals(rejected.reason().description(), rejected.getMessage(),
                "拒绝信息必须是固定文案");
        return rejected.reason();
    }
}
