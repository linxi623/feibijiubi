package com.feibijiubi.backend.interaction.service;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionActorResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T15 第二阶段身份解析的纯单元测试：不启动 Spring，不连任何数据库/Redis。
 *
 * <p>断言的是“哪些原文永远不往下传”以及“域分离前缀确实生效”：哈希输入串在这里被独立重算，
 * 因此改动前缀、漏掉前缀、或把 uid 直接当哈希返回都会被本类立刻抓住。</p>
 */
class VideoInteractionActorResolverTests {

    /** 与实现约定的域分离前缀（协议常量，公开、非密钥）。 */
    private static final String USER_DOMAIN = "video-interaction:actor:v1:user:";
    private static final String ANONYMOUS_DOMAIN = "video-interaction:actor:v1:anonymous:";

    private final VideoInteractionActorResolver resolver = new VideoInteractionActorResolver();

    @Test
    void loggedInUserIsPseudonymisedWithUserDomainPrefix() {
        VideoInteractionActor actor = resolver.resolve(42, null);

        assertEquals(VideoInteractionActorType.USER, actor.type());
        assertEquals(sha256Hex(USER_DOMAIN + 42), actor.hash());
        assertEquals(64, actor.hash().length());
        assertEquals(actor.hash().toLowerCase(), actor.hash(), "哈希必须是小写，避免同一 actor 两种写法");
        assertTrue(actor.hash().matches("[0-9a-f]{64}"),
                "只能往上传规范化 64 hex，不能是 uid 原文或其它格式");
    }

    @Test
    void visitorHeaderIsIgnoredCompletelyForLoggedInUsers() {
        String hash = resolver.resolve(42, null).hash();

        assertEquals(hash, resolver.resolve(42, "not-a-uuid").hash(),
                "登录态下非法 visitor 头也必须被忽略，不能让它把请求变成 400");
        assertEquals(hash, resolver.resolve(42, UUID.randomUUID().toString()).hash(),
                "登录态下合法 visitor 头同样不使用，否则同一用户可以有第二个身份");
    }

    @Test
    void anonymousVisitorMustProvideCanonicalUuid() {
        String visitorId = UUID.randomUUID().toString();

        VideoInteractionActor anonymous = resolver.resolve(null, visitorId);

        assertEquals(VideoInteractionActorType.ANONYMOUS, anonymous.type());
        assertEquals(sha256Hex(ANONYMOUS_DOMAIN + visitorId), anonymous.hash());

        for (String bad : new String[]{null, "", "  ", "not-a-uuid", "1-1-1-1-1",
                visitorId.replace("-", ""), visitorId + "0",
                UUID.randomUUID() + "-extra"}) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> resolver.resolve(null, bad), "应拒绝非 canonical UUID: " + bad);
            assertEquals(400, error.getCode());
        }
    }

    @Test
    void uuidCaseIsNormalisedSoOneVisitorCannotGetTwoIdentities() {
        String lower = UUID.randomUUID().toString().toLowerCase();

        assertEquals(resolver.resolve(null, lower).hash(),
                resolver.resolve(null, lower.toUpperCase()).hash(),
                "同一 UUID 的大小写必须归一到同一个哈希");
    }

    @Test
    void userAndAnonymousDomainsCannotCollide() {
        String visitorId = UUID.randomUUID().toString();

        assertNotEquals(resolver.resolve(42, null).hash(),
                resolver.resolve(null, visitorId).hash(),
                "USER 与 ANONYMOUS 必须是两个身份空间");

        // 极端反例：把 visitor 造成“纯数字 uid”的形状也不可能与 uid 哈希相同，
        // 因为前缀不同；这里用同一个数字文本证明域分离起了作用。
        assertEquals(sha256Hex(USER_DOMAIN + 42), resolver.resolve(42, null).hash());
        assertNotEquals(sha256Hex(USER_DOMAIN + 42), sha256Hex(ANONYMOUS_DOMAIN + 42));
    }

    @Test
    void hashingIsStableAcrossCalls() {
        assertEquals(resolver.resolve(9, null).hash(), resolver.resolve(9, null).hash());
        String visitorId = UUID.randomUUID().toString();
        assertEquals(resolver.resolve(null, visitorId).hash(),
                resolver.resolve(null, visitorId).hash());
    }

    @Test
    void hashDoesNotContainRawVisitorIdOrUid() {
        String visitorId = UUID.randomUUID().toString();
        VideoInteractionActor anonymous = resolver.resolve(null, visitorId);

        assertFalse(anonymous.hash().contains(visitorId.replace("-", "")));
        assertFalse(anonymous.hash().contains(visitorId));
        assertTrue(anonymous.hash().matches("[0-9a-f]{64}"));
    }

    private static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
