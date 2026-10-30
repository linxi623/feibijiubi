package com.feibijiubi.backend.videostats.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T04 配置绑定的轻量上下文测试。
 *
 * <p>只注册 {@link VideoStatusProperties}（通过 {@code @EnableConfigurationProperties}）与
 * {@link VideoStatusRuntimeRoles}，<b>不</b>加载 {@code BackendApplication}、不导入
 * {@code @EnableAutoConfiguration}，因此不会创建 DataSource / Redis / RabbitMQ 连接，
 * 也不会启动任何监听器或调度线程。它补的是纯 Mockito 测试补不到的一段：
 * 配置项名 → 字段绑定 → {@code @PostConstruct} 启动校验 → 角色矩阵，全部走真实 Spring 绑定。</p>
 *
 * <p>与 {@code VideoStatusRuntimeRoleContractTests}的分工：那个类直接 new 对象验证规则；
 * 本类验证“用户写的 key 真的能绑到字段上”（例如 `-enabled` 后缀、kebab-case），
 * 以及非法取值确实在启动阶段让上下文失败。</p>
 */
class VideoStatusRuntimePropertiesContextTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VideoStatusProperties.class)
    @Import(VideoStatusRuntimeRoles.class)
    static class PropertiesOnlyConfiguration {
    }

    /** 无自动配置：只有本测试显式注册的两个 Bean。 */
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesOnlyConfiguration.class);

    // ------------------------------------------------------------ 绑定事实

    @Test
    void defaultBindingHasNoInfrastructureBeans() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));

            VideoStatusProperties properties = context.getBean(VideoStatusProperties.class);
            assertFalse(properties.isAsyncEnabled(), "async-enabled 默认关闭");
            assertTrue(properties.isOutboxRelayEnabled(), "outbox-relay-enabled 默认 true，历史部署不会被默默停投");
            assertFalse(properties.isSchedulingEnabled());
            assertEquals(30, properties.getRedisEventTtlDays());

            assertEquals(1, context.getBeanNamesForType(VideoStatusRuntimeRoles.class).length);

            // 只有配置类 + 两个 Bean，没有任何 DB/Redis/MQ 基础设施。
            assertNoInfrastructure(context);
        });
    }

    @Test
    void relayFalseIsActuallyBoundAndOnlyRemovesRelayRole() {
        runner.withPropertyValues(
                        "app.video-status.async-enabled=true",
                        "app.video-status.outbox-relay-enabled=false",
                        "app.video-status.scheduling-enabled=true",
                        "spring.rabbitmq.listener.simple.auto-startup=true")
                .run(context -> {
                    assertNull(context.getStartupFailure(), failureMessage(context));

                    VideoStatusProperties properties = context.getBean(VideoStatusProperties.class);
                    assertFalse(properties.isOutboxRelayEnabled(), "显式 false 必须覆盖默认 true");

                    VideoStatusRuntimeRoles roles = context.getBean(VideoStatusRuntimeRoles.class);
                    assertEquals(new VideoStatusRuntimeRoles.RoleMatrix(true, false, true, true),
                            roles.matrix(), "停投只关闭投递角色");
                    assertEquals(1, roles.diagnostics().size(), roles.diagnostics().toString());
                    assertTrue(roles.diagnostics().get(0).contains("outbox-relay-enabled=false"),
                            roles.diagnostics().get(0));
                    assertNoInfrastructure(context);
                });
    }

    // ------------------------------------------------------------ 四角色组合

    @Test
    void allFourRolesAreLegalWhenExplicitlyEnabled() {
        runner.withPropertyValues(
                        "app.video-status.async-enabled=true",
                        "app.video-status.outbox-relay-enabled=true",
                        "app.video-status.scheduling-enabled=true",
                        "spring.rabbitmq.listener.simple.auto-startup=true")
                .run(context -> {
                    assertNull(context.getStartupFailure(), failureMessage(context));

                    VideoStatusRuntimeRoles roles = context.getBean(VideoStatusRuntimeRoles.class);
                    assertEquals(new VideoStatusRuntimeRoles.RoleMatrix(true, true, true, true),
                            roles.matrix());
                    assertTrue(roles.diagnostics().isEmpty(), roles.diagnostics().toString());
                    assertNoInfrastructure(context);
                });
    }

    @Test
    void asyncWithoutFlushOrListenerStartsButIsDiagnosed() {
        // 多角色部署 / 维护窗口的合法组合：启动不能失败，只能被诊断。
        runner.withPropertyValues(
                        "app.video-status.async-enabled=true",
                        "app.video-status.outbox-relay-enabled=true",
                        "app.video-status.scheduling-enabled=false",
                        "spring.rabbitmq.listener.simple.auto-startup=false")
                .run(context -> {
                    assertNull(context.getStartupFailure(), failureMessage(context));

                    VideoStatusRuntimeRoles roles = context.getBean(VideoStatusRuntimeRoles.class);
                    assertEquals(new VideoStatusRuntimeRoles.RoleMatrix(true, true, false, false),
                            roles.matrix());
                    String diagnostics = String.join("\n", roles.diagnostics());
                    assertTrue(diagnostics.contains("scheduling-enabled=false"), diagnostics);
                    assertTrue(diagnostics.contains("无人落库"), diagnostics);
                    assertTrue(diagnostics.contains("监听器未自动启动"), diagnostics);
                    assertNoInfrastructure(context);
                });
    }

    // ------------------------------------------------------------ 启动期失败

    @Test
    void outOfRangeBatchSizeFailsDuringPostConstruct() {
        runner.withPropertyValues(
                        "app.video-status.async-enabled=true",
                        "app.video-status.outbox-batch-size=0")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "非法批量必须在绑定后的 @PostConstruct 失败");
                    String messages = failureMessage(context);
                    assertTrue(messages.contains("app.video-status.outbox-batch-size"), messages);
                    assertTrue(messages.contains("当前值：0"), messages);
                });
    }

    @Test
    void replayWindowOutsideIdempotencyTtlFailsDuringPostConstruct() {
        runner.withPropertyValues(
                        "app.video-status.async-enabled=true",
                        "app.video-status.redis-event-ttl-days=1",
                        "app.video-status.consumer-recovery-auto-replay-max-age-seconds=86400")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "重放窗口 >= 幂等 TTL 必须启动失败");
                    String messages = failureMessage(context);
                    assertTrue(messages.contains("consumer-recovery-auto-replay-max-age-seconds"), messages);
                    assertTrue(messages.contains("86400"), messages);
                });
    }

    @Test
    void rebuildPagingBoundsAreBoundFromKebabCaseKeys() {
        runner.withPropertyValues(
                        "app.video-status.rebuild-candidate-page-size=500",
                        "app.video-status.rebuild-max-candidates=200000",
                        "app.video-status.rebuild-snapshot-budget-ms=2500")
                .run(context -> {
                    assertNull(context.getStartupFailure(), failureMessage(context));
                    VideoStatusProperties properties = context.getBean(VideoStatusProperties.class);
                    assertEquals(500, properties.getRebuildCandidatePageSize());
                    assertEquals(200_000, properties.getRebuildMaxCandidates());
                    assertEquals(2_500L, properties.getRebuildSnapshotBudgetMs());
                });
    }

    @Test
    void rebuildPagingBoundsDefaultToTheDocumentedUpperBound() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(), failureMessage(context));
            VideoStatusProperties properties = context.getBean(VideoStatusProperties.class);
            assertEquals(VideoStatusProperties.MAX_REBUILD_CANDIDATE_PAGE_SIZE,
                    properties.getRebuildCandidatePageSize());
            assertEquals(100_000, properties.getRebuildMaxCandidates());
            assertTrue(properties.getRebuildSnapshotBudgetMs() > 0);
        });
    }

    @Test
    void rebuildPageSizeAboveOneThousandFailsDuringPostConstruct() {
        runner.withPropertyValues(
                        "app.video-status.rebuild-candidate-page-size="
                                + (VideoStatusProperties.MAX_REBUILD_CANDIDATE_PAGE_SIZE + 1))
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "页大小超过 1000 必须启动失败");
                    String messages = failureMessage(context);
                    assertTrue(messages.contains("app.video-status.rebuild-candidate-page-size"), messages);
                    assertTrue(messages.contains("1001"), messages);
                });
    }

    @Test
    void rebuildMaxCandidatesAndBudgetMustBePositive() {
        runner.withPropertyValues(
                        "app.video-status.rebuild-max-candidates=0")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "候选上限 0 必须启动失败");
                    String messages = failureMessage(context);
                    assertTrue(messages.contains("app.video-status.rebuild-max-candidates"), messages);
                });
        runner.withPropertyValues(
                        "app.video-status.rebuild-snapshot-budget-ms=0")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "预算 0 必须启动失败");
                    String messages = failureMessage(context);
                    assertTrue(messages.contains("app.video-status.rebuild-snapshot-budget-ms"), messages);
                });
    }

    // ------------------------------------------------------------ helpers

    /** 证明这个轻量上下文没有拉进任何数据库 / Redis / RabbitMQ 基础设施。 */
    private static void assertNoInfrastructure(AssertableApplicationContext context) {
        assertEquals(0, context.getBeanNamesForType(DataSource.class).length, "不得创建 DataSource");
        assertEquals(0, context.getBeanNamesForType(JdbcTemplate.class).length, "不得创建 JdbcTemplate");
        assertEquals(0, context.getBeanNamesForType(RedisConnectionFactory.class).length,
                "不得创建 Redis 连接工厂");
        assertEquals(0, context.getBeanNamesForType(ConnectionFactory.class).length,
                "不得创建 RabbitMQ 连接工厂");
        assertEquals(0, context.getBeanNamesForType(RabbitListenerContainerFactory.class).length,
                "不得创建监听容器（也就不会消费消息）");
    }

    private static String failureMessage(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        if (failure == null) {
            return "";
        }
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(current.getMessage())
                    .append('\n');
        }
        return messages.toString();
    }
}
