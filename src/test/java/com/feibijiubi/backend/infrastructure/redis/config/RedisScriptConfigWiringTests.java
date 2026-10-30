package com.feibijiubi.backend.infrastructure.redis.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5：验证 {@link RedisScriptConfig} 搬进 {@code infrastructure.redis.config} 后，Lua 脚本 bean 的
 * 名字、类型、来源资源和 {@code @Primary} 都没有变化。
 *
 * <p>本类不连 Redis：{@link DefaultRedisScript} 只在上下文初始化时读取 classpath 上的 {@code lua/*.lua}，
 * 不建立连接、不执行脚本。断言直接比对 bean 里的脚本文本与资源文件内容，
 * 因此“脚本资源路径没搬、内容没改”是被证明的，而不是靠阅读代码。</p>
 */
class RedisScriptConfigWiringTests {

    private static final String OLD_CONFIG_FQCN = "com.feibijiubi.backend.config.RedisScriptConfig";
    private static final String OLD_REDIS_UTILS_FQCN = "com.feibijiubi.backend.utils.redis.RedisUtils";

    /** bean 名 → (Lua 资源路径, 结果类型)，取自搬迁前的 {@code RedisScriptConfig}。 */
    private static final Map<String, ScriptExpectation> EXPECTED_SCRIPTS = expectedScripts();

    @Configuration(proxyBeanMethods = false)
    @Import(RedisScriptConfig.class)
    static class ScriptOnlyConfiguration {
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ScriptOnlyConfiguration.class);

    @Test
    void registersTheSameFiveLuaScriptsFromTheSameClasspathResources() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));

            assertEquals(EXPECTED_SCRIPTS.keySet(),
                    Set.of(context.getBeanNamesForType(DefaultRedisScript.class)),
                    "脚本 bean 名集合不得因搬包改变");

            EXPECTED_SCRIPTS.forEach((beanName, expectation) -> {
                DefaultRedisScript<?> script = assertInstanceOf(
                        DefaultRedisScript.class, context.getBean(beanName), beanName);
                assertEquals(expectation.resultType(), script.getResultType(),
                        beanName + " 的结果类型不得改变");
                assertEquals(expectation.scriptText(), script.getScriptAsString(),
                        beanName + " 仍必须加载 " + expectation.resourcePath() + " 且内容不变");
            });
        });
    }

    @Test
    void primaryFlagsStayExactlyAsDeclaredBeforeTheMove() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));

            EXPECTED_SCRIPTS.keySet().forEach(beanName -> {
                // 搬迁前只有固定窗口限流脚本与统计自增脚本带 @Primary，其余三个按名字注入。
                boolean expectedPrimary = Set.of("fixedWindowRateLimitScript", "videoStatusIncrementScript")
                        .contains(beanName);
                assertEquals(expectedPrimary,
                        context.getBeanFactory().getBeanDefinition(beanName).isPrimary(),
                        beanName + " 的 @Primary 状态不得改变");
            });
        });
    }

    @Test
    void redisCapabilityHasASingleHomeThatTheRootScanStillFinds() {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Configuration.class));

        Set<String> configs = new LinkedHashSet<>();
        provider.findCandidateComponents("com.feibijiubi.backend.infrastructure.redis")
                .forEach(candidate -> configs.add(candidate.getBeanClassName()));

        assertTrue(configs.contains(RedisScriptConfig.class.getName()),
                "根包扫描必须能发现搬包后的脚本配置：" + configs);
        assertTrue(new ClassPathResource("lua/fixed-window-rate-limit.lua").exists(),
                "Lua 资源物理路径保持不变");
        assertThrows(ClassNotFoundException.class, () -> Class.forName(OLD_CONFIG_FQCN),
                "旧包不得残留同类（同一类只存在一份）");
        assertThrows(ClassNotFoundException.class, () -> Class.forName(OLD_REDIS_UTILS_FQCN),
                "旧 utils 包不得残留 Redis 工具类");
    }

    private static Map<String, ScriptExpectation> expectedScripts() {
        Map<String, ScriptExpectation> scripts = new LinkedHashMap<>();
        scripts.put("fixedWindowRateLimitScript",
                new ScriptExpectation("lua/fixed-window-rate-limit.lua", Long.class));
        scripts.put("videoStatusIncrementScript",
                new ScriptExpectation("lua/video-status-increment.lua", String.class));
        scripts.put("videoStatusReleaseGateScript",
                new ScriptExpectation("lua/video-status-release-gate.lua", Long.class));
        scripts.put("videoStatusInitScript",
                new ScriptExpectation("lua/video-status-init.lua", String.class));
        scripts.put("videoStatusReplaceScript",
                new ScriptExpectation("lua/video-status-replace.lua", String.class));
        return Collections.unmodifiableMap(scripts);
    }

    /** 期望的脚本来源与结果类型；{@link #scriptText()} 直接读 classpath 资源。 */
    private record ScriptExpectation(String resourcePath, Class<?> resultType) {

        String scriptText() {
            try {
                return new ClassPathResource(resourcePath)
                        .getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("缺少 Lua 资源：" + resourcePath, e);
            }
        }
    }

    private static String failureMessage(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        return failure == null ? "" : failure.toString();
    }
}
