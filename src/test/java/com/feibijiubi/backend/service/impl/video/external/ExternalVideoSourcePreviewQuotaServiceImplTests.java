package com.feibijiubi.backend.service.impl.video.external;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.common.RedisOperationException;
import com.feibijiubi.backend.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M3-B：每管理员预览配额的纯单测（不连 Redis，{@link RedisUtils} 用 mock）。
 *
 * <p>固定三件事：窗口是 {@code 60} 秒且只传一个计数键；键只含登录 uid，目录与详情共用同一个键；
 * 超过上限是 429、脚本返回 {@code null}/负数是 fail-closed、Redis 失败原样上抛（由全局处理器映射 503）。
 * 这些断言不证明 Redis 真实行为，也不证明跨实例共享生效 —— 那需要真实 Redis。</p>
 */
class ExternalVideoSourcePreviewQuotaServiceImplTests {

    private static final Integer ADMIN_UID = 42;
    private static final Integer OTHER_ADMIN_UID = 43;
    /** 固定前缀（部署约定的键空间）＋ 登录 uid，就是本服务的整个键。 */
    private static final String KEY_PREFIX = "rate:external-video-source:preview:admin:";
    private static final String EXPECTED_KEY = KEY_PREFIX + ADMIN_UID;

    private final RedisUtils redisUtils = mock(RedisUtils.class);
    /** 真实脚本对象只作为 bean 标识传给被 mock 的 {@link RedisUtils}，不会被执行。 */
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    private final ExternalVideoSourceProperties properties = properties(30);

    @Test
    void consumesOneUnitUnderAFixedSixtySecondWindowOnThePerAdminKey() {
        when(redisUtils.executeScript(same(script), eq(List.of(EXPECTED_KEY)), eq("60")))
                .thenReturn(1L);

        assertDoesNotThrow(() -> service().checkAndConsume(ADMIN_UID));

        verify(redisUtils).executeScript(same(script), eq(List.of(EXPECTED_KEY)), eq("60"));
    }

    @Test
    void exactlyAtTheLimitIsAllowedAndOneAboveIs429() {
        when(redisUtils.executeScript(same(script), eq(List.of(EXPECTED_KEY)), eq("60")))
                .thenReturn(30L);
        assertDoesNotThrow(() -> service().checkAndConsume(ADMIN_UID));

        when(redisUtils.executeScript(same(script), eq(List.of(EXPECTED_KEY)), eq("60")))
                .thenReturn(31L);
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service().checkAndConsume(ADMIN_UID));

        assertEquals(429, failure.getCode());
        assertTrue(failure.getMessage().contains("频繁"), failure.getMessage());
        assertTrue(failure.getMessage().indexOf("30") < 0,
                "超限文案不暴露额度上限或剩余额度：" + failure.getMessage());
    }

    @Test
    void nullOrNegativeScriptResultIsFailClosed() {
        when(redisUtils.executeScript(same(script), eq(List.of(EXPECTED_KEY)), eq("60")))
                .thenReturn(null);
        assertThrows(RedisOperationException.class, () -> service().checkAndConsume(ADMIN_UID));

        when(redisUtils.executeScript(same(script), eq(List.of(EXPECTED_KEY)), eq("60")))
                .thenReturn(-1L);
        assertThrows(RedisOperationException.class, () -> service().checkAndConsume(ADMIN_UID));
    }

    @Test
    void redisFailureIsNotSwallowed() {
        when(redisUtils.executeScript(same(script), eq(List.of(EXPECTED_KEY)), eq("60")))
                .thenThrow(new RedisOperationException("Redis 操作失败"));

        assertThrows(RedisOperationException.class, () -> service().checkAndConsume(ADMIN_UID),
                "Redis 不可用必须 fail-closed，绝不因为“查不到”就放行");
    }

    @Test
    void missingAdminUserIdIs401AndNeverReachesRedis() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service().checkAndConsume(null));

        assertEquals(401, failure.getCode());
        verifyNoInteractions(redisUtils);
    }

    @Test
    void catalogAndDetailShareOneQuotaKeyPerAdmin() {
        // 断言的是"键里唯一的变量是登录 uid"：所以必须看真正传给 Redis 的键，而不是比对字面量。
        List<List<String>> capturedKeys = new ArrayList<>();
        when(redisUtils.executeScript(same(script), anyList(), eq("60")))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    capturedKeys.add(new ArrayList<>(keys));
                    return (long) capturedKeys.size();
                });

        ExternalVideoSourcePreviewQuotaServiceImpl quotaService = service();
        quotaService.checkAndConsume(ADMIN_UID);        // 目录预览
        quotaService.checkAndConsume(ADMIN_UID);        // 详情预览
        quotaService.checkAndConsume(OTHER_ADMIN_UID);  // 另一个管理员

        verify(redisUtils, times(3)).executeScript(same(script), anyList(), eq("60"));
        assertEquals(3, capturedKeys.size(), "每次准入只做一次计数");

        assertEquals(List.of(EXPECTED_KEY), capturedKeys.get(0), "只传一个计数键：固定前缀 + 登录 uid");
        assertEquals(capturedKeys.get(0), capturedKeys.get(1),
                "目录与详情共用同一个键：同一个 uid 的键不随业务维度变化");
        assertEquals(List.of(KEY_PREFIX + OTHER_ADMIN_UID), capturedKeys.get(2),
                "换一个管理员只换键里的 uid，其它部分完全相同");
        assertNotEquals(capturedKeys.get(0), capturedKeys.get(2), "不同管理员的配额互不共享");
    }

    // ---------------------------------------------------------------- helpers

    private ExternalVideoSourcePreviewQuotaServiceImpl service() {
        return new ExternalVideoSourcePreviewQuotaServiceImpl(script, redisUtils, properties);
    }

    private static ExternalVideoSourceProperties properties(int perAdminRequestsPerMinute) {
        ExternalVideoSourceProperties properties = new ExternalVideoSourceProperties();
        properties.setPerAdminRequestsPerMinute(perAdminRequestsPerMinute);
        return properties;
    }
}
