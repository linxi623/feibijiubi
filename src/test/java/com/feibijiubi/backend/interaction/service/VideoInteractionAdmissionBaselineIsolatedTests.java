package com.feibijiubi.backend.interaction.service;

import com.feibijiubi.backend.infrastructure.redis.config.RedisScriptConfig;
import com.feibijiubi.backend.interaction.config.VideoInteractionProperties;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.videostats.entity.VideoStatus;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.videostats.mq.VideoStatusBatchFlushScheduler;
import com.feibijiubi.backend.videostats.mq.VideoStatusDirtyRecoveryScheduler;
import com.feibijiubi.backend.videostats.mq.VideoStatusEventConsumer;
import com.feibijiubi.backend.videostats.mq.VideoStatusOutboxRelay;
import com.feibijiubi.backend.videostats.mq.VideoStatusStatus0CompensationScheduler;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionActorResolver;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionAdmissionServiceImpl;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionCleanupScheduler;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionRateLimitServiceImpl;
import com.feibijiubi.backend.interaction.service.impl.VideoInteractionServiceImpl;
import com.feibijiubi.backend.service.video.baseline.VideoInteractionAdmissionBaselineSummary;
import com.feibijiubi.backend.videostats.service.VideoStatusService;
import com.feibijiubi.backend.shared.JsonUtils;
import com.feibijiubi.backend.infrastructure.redis.RedisUtils;
import com.feibijiubi.backend.interaction.vo.VideoInteractionVO;
import com.feibijiubi.backend.videostats.service.VideoStatusIsolatedIntegrationTests;
import com.feibijiubi.backend.video.service.PublishedVideoGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T16-B0：<b>同步准入基线的测试环境测量片</b>。只测“真实 admission 服务 + 同步计数”的耗时，
 * 以及一条普通 SELECT 的读后可见性（可见性由断言证明，不由耗时证明）。不测异步端到端、MQ 投递/消费、
 * 恢复、并发或容量，也不声称测到真实 commit 时间。
 *
 * <p>只在显式 JVM 参数 {@code -DvideoStatusBaseline=true} 时运行（默认跳过）。复用
 * {@link VideoStatusIsolatedIntegrationTests.IsolationGuard} 与 {@code video-status-isolated.properties}：
 * 正式环境的 datasource / Redis 端点会在建连之前被拒，本类不加载 {@code application.yml}。
 * <b>本轮只编译，未启动隔离栈、未运行</b>。</p>
 *
 * <h2>本片刻意不包含的东西</h2>
 * <ul>
 *   <li>{@code app.video-status.async-enabled=false}：同步计数直接更新 {@code video_status}，
 *       所以“提交后是否立即可见”可以用一条普通 SELECT 判定；异步路径的最终一致延迟<b>不在</b>本片范围。</li>
 *   <li>不产生 Outbox 待处理事件、不产生 consumed 事件：{@code VideoStatusService} 被换成一个
 *       一旦被调用就抛 {@link AssertionError} 的桩，{@code createEvent} 绝不可达；</li>
 *   <li>显式排除 RabbitMQ 自动配置（{@code RabbitAutoConfiguration}），上下文里断言不存在
 *       {@code RabbitTemplate} / {@code AmqpAdmin} / {@code ConnectionFactory} / listener 容器 /
 *       {@code RabbitListenerEndpointRegistry}，也不存在 relay / flush / recovery / 补偿 / 清理调度器 bean；</li>
 *   <li>{@code app.video-interaction.cleanup-enabled=false}：本片<b>不删除任何行</b>——不删 DB 行、
 *       不删 Redis key、不动 dirty/gate/processed、不清队列。seed 数据留在隔离库里（报告里记录数量）。</li>
 * </ul>
 *
 * <h2>测量口径（必须连同数字一起读）</h2>
 * <ul>
 *   <li>{@code admission}：调用前 {@code System.nanoTime()} → {@code admissionService.record(...)} 返回。
 *       包含参数校验、视频可见性查询、幂等重放查询（未命中）、<b>两次 Redis 固定窗口准入脚本往返</b>、
 *       以及同一 MySQL 事务（占位 → 窗口判定 → 计数 → 终态）的提交。</li>
 *   <li>{@code post-return read}：admission <b>返回之后</b>到
 *       {@code SELECT play_times FROM video_status WHERE vid=?} 读回完成（含取连接、网络往返、语句解析）。
 *       commit 发生在 {@code record()} 返回<b>之前</b>，本区间<b>不含</b> commit → 返回那一段，
 *       因此它<b>不是</b> commit 耗时，也<b>不是</b> commit → 可见区间的上界（真实区间严格更长）。
 *       可见性由断言的确切值证明，不由这个耗时证明；本片<b>不</b>测异步端到端延迟，也不声称测到真实 commit 时间。</li>
 *   <li>同步路径下每个请求之后立刻用确切 oracle 断言计数（{@code 1..n}），**没有** poll 循环：
 *       提交一旦返回就必须可见，这正是“同步计数”的定义。</li>
 *   <li>warmup 每个场景 20 条、各自独立 vid，<b>计入 oracle 但完全不计入任何分位数</b>。</li>
 *   <li>固定小样本、单 JVM、串行：<b>不是</b>容量上限、吞吐、SLA 或生产验收结论。</li>
 * </ul>
 *
 * <h2>只读预检（不自动迁移、不以跳过代替失败）</h2>
 * <p>预检只查“表是否存在”，并按来源分组报缺失：{@code video} / {@code video_status} /
 * {@code video_status_outbox} 属<b>基础 schema</b>（{@code database/feibijiubi.sql}），
 * {@code video_interaction_request} / {@code video_interaction_window} 属<b>T15 迁移</b>
 * （{@code database/migrations/t15-video-interaction-idempotency.sql}）。T15 迁移<b>不</b>建 outbox 表，
 * 所以缺 outbox 只会指向基础 schema，不会被说成“去执行 T15 迁移”。清理索引不在预检范围（本片不跑清理）。</p>
 *
 * <p>运行：{@code ./mvnw -DvideoStatusBaseline=true -Dtest=VideoInteractionAdmissionBaselineIsolatedTests test}
 * （需要 {@code docker compose -f compose.test.yaml up -d --wait} 且先执行过 T15 幂等/窗口表迁移）。</p>
 */
@EnabledIfSystemProperty(named = "videoStatusBaseline", matches = "true")
@SpringBootTest(classes = VideoInteractionAdmissionBaselineIsolatedTests.TestApplication.class,
        properties = {
                "spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none",
                // 只量同步计数模式。
                "app.video-status.async-enabled=false",
                "app.video-status.outbox-relay-enabled=false",
                "app.video-status.scheduling-enabled=false",
                // 清理显式关闭：本片一行都不删。
                "app.video-interaction.cleanup-enabled=false"
        })
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VideoInteractionAdmissionBaselineIsolatedTests {

    /** 单视频场景的新请求数（样本数）。 */
    private static final int SINGLE_VIDEO_REQUESTS = 200;
    /** 多视频场景的视频数。 */
    private static final int TEN_VIDEO_COUNT = 10;
    /** 多视频场景每个视频的新请求数（合计 200）。 */
    private static final int REQUESTS_PER_VIDEO = 20;
    /** 每个场景的 warmup 新请求数：独立 vid、不计入分位数。 */
    private static final int WARMUP_REQUESTS = 20;
    /** 复用已成功 requestKey 的重放次数：不新增计数。 */
    private static final int REPLAY_REQUESTS = 100;

    /** 本片期望留下的请求行数（不含重放：重放不建新行）。 */
    private static final int EXPECTED_REQUEST_ROWS = SINGLE_VIDEO_REQUESTS
            + TEN_VIDEO_COUNT * REQUESTS_PER_VIDEO
            + 2 * WARMUP_REQUESTS;

    /**
     * 预检分组一：<b>基础 schema</b> 表，由 {@code database/feibijiubi.sql} 建立（T15 迁移不建它们）。
     *
     * <p>{@code video_status_outbox} 在这里是因为本片虽然不产生 Outbox 事件，但会用
     * {@code SELECT COUNT(*) ... WHERE aggregate_id IN (...)} 断言“本轮 0 条”，它是真实查询的表。</p>
     */
    private static final List<String> BASE_SCHEMA_TABLES =
            List.of("video", "video_status", "video_status_outbox");

    /** 预检分组二：T15 交互表，由 {@code t15-video-interaction-idempotency.sql} 建立。 */
    private static final List<String> T15_MIGRATION_TABLES =
            List.of("video_interaction_request", "video_interaction_window");

    @Configuration(proxyBeanMethods = false)
    @Profile("video-status-isolated")
    @EnableAutoConfiguration(excludeName = {
            "org.redisson.spring.starter.RedissonAutoConfigurationV2",
            // 本片是纯同步基线：Rabbit 自动配置整体排除，因此不会有 ConnectionFactory /
            // RabbitTemplate / AmqpAdmin / listener 容器 / @RabbitListener 注册表。
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
    })
    @EnableConfigurationProperties({VideoStatusProperties.class, VideoInteractionProperties.class})
    // R3：交互/视频 mapper 已按模块迁走。
    // R4：统计 mapper 迁到 videostats.mapper，旧 com.feibijiubi.backend.mapper 已无接口，
    // 该扫描项按等价集合改为 videostats.mapper（不是缩小扫描范围）。
    @MapperScan({"com.feibijiubi.backend.videostats.mapper", "com.feibijiubi.backend.video.mapper",
            "com.feibijiubi.backend.interaction.mapper"})
    @Import({RedisScriptConfig.class,
            PublishedVideoGuard.class,
            VideoInteractionServiceImpl.class,
            VideoInteractionActorResolver.class,
            VideoInteractionRateLimitServiceImpl.class,
            VideoInteractionAdmissionServiceImpl.class,
            RedisUtils.class, JsonUtils.class})
    static class TestApplication {
        /**
         * 同步计数不得创建统计事件：任何调用都是本片要抓的缺陷，所以直接抛错而不是返回 null。
         * 这也是“本片绝不产生 Outbox 待处理事件 / consumed 事件”的结构性保证。
         */
        @Bean
        VideoStatusService unusedVideoStatusService() {
            return new VideoStatusService() {
                @Override
                public ApplyResult apply(VideoStatusChangedEvent event) {
                    throw new AssertionError("同步计数路径不得调用 apply()");
                }

                @Override
                public VideoStatusChangedEvent createEvent(Integer vid, VideoStatusEventType type,
                                                           long delta) {
                    throw new AssertionError("同步计数路径不得创建统计事件");
                }

                @Override
                public VideoStatus getByVid(Integer vid) {
                    throw new AssertionError("本基线片不得读取 Redis 统计");
                }
            };
        }
    }

    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired VideoInteractionAdmissionService admissionService;
    @Autowired VideoInteractionActorResolver actorResolver;
    @Autowired VideoInteractionProperties interactionProperties;
    @Autowired VideoStatusProperties videoStatusProperties;

    // ------------------------------------------------------------------ preflight

    /**
     * 只读预检：基础 schema（{@code video} / {@code video_status} / {@code video_status_outbox}）与
     * T15 两张表（请求幂等 + 窗口）必须已经存在。
     *
     * <p>缺表时立即失败，并<b>按来源分别归因</b>：基础 schema 表指向 {@code database/feibijiubi.sql}，
     * T15 表指向 {@code database/migrations/t15-video-interaction-idempotency.sql}。T15 迁移不建
     * outbox 表，所以缺 outbox 时绝不会说成“去执行 T15 迁移”。本测试<b>绝不</b>自动建表/迁移，
     * 也<b>绝不</b>把缺表当成“跳过并通过”——一个因为环境没准备好而变绿的基线测量比失败更危险。</p>
     */
    @Test
    void preflightNamesTableSourceAndRefusesToMigrateOrSkip() {
        // 环境必须已经就绪（缺表时这里就红，而不是带着缺表跑完测量）。
        requireBaselineTables();

        IllegalStateException t15 = assertThrows(IllegalStateException.class,
                () -> requireTables(List.of(), List.of("video_interaction_window_absent_probe")));
        assertTrue(t15.getMessage().contains("video_interaction_window_absent_probe"),
                "失败信息必须点名缺哪张表，实际：" + t15.getMessage());
        assertTrue(t15.getMessage().contains("t15-video-interaction-idempotency.sql"),
                "缺 T15 表时必须指向 T15 迁移，实际：" + t15.getMessage());
        assertTrue(t15.getMessage().contains("不会自动"),
                "必须明确本测试不自动迁移、不以跳过代替失败，实际：" + t15.getMessage());
        assertFalse(t15.getMessage().contains("database/feibijiubi.sql"),
                "缺 T15 表时不得归因于基础 schema，实际：" + t15.getMessage());

        IllegalStateException baseSchema = assertThrows(IllegalStateException.class,
                () -> requireTables(List.of("video_status_outbox_absent_probe"), List.of()));
        assertTrue(baseSchema.getMessage().contains("video_status_outbox_absent_probe"),
                "失败信息必须点名缺哪张表，实际：" + baseSchema.getMessage());
        assertTrue(baseSchema.getMessage().contains("database/feibijiubi.sql"),
                "缺基础 schema 表时必须指向基础 schema，实际：" + baseSchema.getMessage());
        assertTrue(baseSchema.getMessage().contains("不会自动"));
        assertFalse(baseSchema.getMessage().contains("t15-video-interaction-idempotency.sql"),
                "T15 迁移不建 outbox 表，不得声称缺 outbox 要跑 T15 迁移，实际："
                        + baseSchema.getMessage());
    }

    /**
     * 结构性断言：本上下文没有 MQ、没有 listener、没有 relay/调度器，配置项也保持“同步 + 清理关闭 + 默认额度”。
     *
     * <p>额度断言同时证明测量<b>没有</b>靠调高配置来规避 429：每条请求都换一个全新 canonical visitor，
     * 所以在默认 60/60/120 下永远不会触发窗口抑制或超限。</p>
     */
    @Test
    void contextHasNoRabbitListenerRelaySchedulerAndKeepsSynchronousDefaults() {
        assertFalse(videoStatusProperties.isAsyncEnabled(), "本片只量同步计数模式");
        assertFalse(videoStatusProperties.isSchedulingEnabled());
        assertFalse(videoStatusProperties.isOutboxRelayEnabled());
        assertFalse(interactionProperties.isCleanupEnabled(), "清理必须关闭：本片不删除任何行");

        assertEquals(60, interactionProperties.getWindowSeconds());
        assertEquals(60, interactionProperties.getMaxRequestsPerAction());
        assertEquals(120, interactionProperties.getMaxRequestsPerActor());

        assertNoBeansOfType(
                // MQ 自动配置已排除：连连接工厂都不该存在。
                ConnectionFactory.class,
                RabbitTemplate.class,
                RabbitAdmin.class,
                MessageListenerContainer.class,
                RabbitListenerEndpointRegistry.class,
                // 视频统计管道与清理的调度/消费/中继组件。
                VideoStatusEventConsumer.class,
                VideoStatusOutboxRelay.class,
                VideoStatusBatchFlushScheduler.class,
                VideoStatusDirtyRecoveryScheduler.class,
                VideoStatusStatus0CompensationScheduler.class,
                VideoInteractionCleanupScheduler.class);

        // 统计服务是抛错桩：证明同步路径没有可用的异步入口。
        assertThrows(AssertionError.class,
                () -> context.getBean(VideoStatusService.class).getByVid(1));
    }

    // ------------------------------------------------------------------ measurement

    /**
     * 固定小样本、串行、单 JVM 的同步准入基线：两个计数场景 + 一个重放场景。
     *
     * <p>顺序：预检 → seed 自己的 user/video → 逐场景 warmup（独立 vid、不计时）→ 逐请求计时并
     * 用确切 oracle 断言“提交后立即可见” → 重放 100 次验证不新增计数、不推进窗口 →
     * 汇总 Outbox/请求行/窗口行不变量 → 打印报告（不含任何 vid / requestKey / visitor / 凭据）。</p>
     */
    @Test
    void syncAdmissionBaselineReport() {
        requireBaselineTables();
        Seed seed = seedOwnVideos();

        List<Probe> allProbes = new ArrayList<>();
        List<Long> singleAdmission = new ArrayList<>(SINGLE_VIDEO_REQUESTS);
        List<Long> singlePostReturnRead = new ArrayList<>(SINGLE_VIDEO_REQUESTS);
        List<Long> tenAdmission = new ArrayList<>(TEN_VIDEO_COUNT * REQUESTS_PER_VIDEO);
        List<Long> tenPostReturnRead = new ArrayList<>(TEN_VIDEO_COUNT * REQUESTS_PER_VIDEO);
        List<Long> replayAdmission = new ArrayList<>(REPLAY_REQUESTS);

        // --- 场景 1：单视频 200 条新请求（每条 = 全新 visitor + 全新 requestKey）。
        warmUp(seed.singleWarmupVid(), allProbes);
        List<Probe> singleProbes = new ArrayList<>(SINGLE_VIDEO_REQUESTS);
        for (int expected = 1; expected <= SINGLE_VIDEO_REQUESTS; expected++) {
            Probe probe = newProbe();
            allProbes.add(probe);
            singleProbes.add(probe);
            TimedRequest timed = timedCountedPlay(seed.singleVid(), probe, expected);
            singleAdmission.add(timed.admissionNanos());
            singlePostReturnRead.add(timed.postReturnReadNanos());
        }
        assertEquals((long) SINGLE_VIDEO_REQUESTS, playTimes(seed.singleVid()));

        // --- 场景 2：10 个视频共 200 条新请求（每视频 20 条），每个视频独立 oracle。
        warmUp(seed.tenVideoWarmupVid(), allProbes);
        for (int vid : seed.tenVids()) {
            for (int expected = 1; expected <= REQUESTS_PER_VIDEO; expected++) {
                Probe probe = newProbe();
                allProbes.add(probe);
                TimedRequest timed = timedCountedPlay(vid, probe, expected);
                tenAdmission.add(timed.admissionNanos());
                tenPostReturnRead.add(timed.postReturnReadNanos());
            }
            assertEquals((long) REQUESTS_PER_VIDEO, playTimes(vid));
        }
        assertEquals(TEN_VIDEO_COUNT * REQUESTS_PER_VIDEO, tenAdmission.size());

        // --- 场景 3：复用已成功 key 重放 100 次：返回历史结果，不新增计数、不推进窗口。
        List<Probe> replayProbes = singleProbes.subList(0, REPLAY_REQUESTS);
        List<String> windowBefore = windowLastCountedAt(seed.singleVid(), replayProbes);
        for (Probe probe : replayProbes) {
            long start = System.nanoTime();
            VideoInteractionVO replayed = play(seed.singleVid(), probe);
            long returned = System.nanoTime();
            replayAdmission.add(returned - start);

            assertTrue(replayed.duplicate(), "复用已提交的 requestKey 必须是幂等重放");
            assertTrue(replayed.counted(), "重放返回的是原请求的结果（原请求计过数）");
            assertEquals(VideoInteractionVO.REASON_COUNTED, replayed.reason());
        }
        assertEquals((long) SINGLE_VIDEO_REQUESTS, playTimes(seed.singleVid()),
                "重放不得再加一次计数");
        assertEquals(windowBefore, windowLastCountedAt(seed.singleVid(), replayProbes),
                "重放不得推进窗口时间");

        // --- 独立 oracle：每个 vid 的确切计数 + 表级不变量。
        assertEquals((long) WARMUP_REQUESTS, playTimes(seed.singleWarmupVid()));
        assertEquals((long) WARMUP_REQUESTS, playTimes(seed.tenVideoWarmupVid()));
        List<Integer> ownVids = seed.allVids();

        assertEquals(EXPECTED_REQUEST_ROWS, countRows(
                "SELECT COUNT(*) FROM video_interaction_request WHERE vid", ownVids));
        assertEquals(EXPECTED_REQUEST_ROWS, countRows(
                "SELECT COUNT(*) FROM video_interaction_request WHERE state = 1 AND vid", ownVids),
                "所有请求都应真的计过数（没有一条被窗口抑制）");
        assertEquals(0, countRows(
                "SELECT COUNT(*) FROM video_interaction_request WHERE state = 2 AND vid", ownVids));
        assertEquals(0, countRows(
                "SELECT COUNT(*) FROM video_interaction_request "
                        + "WHERE event_id IS NOT NULL AND vid", ownVids),
                "同步路径不写 event_id；有值说明走了异步路径");
        assertEquals(EXPECTED_REQUEST_ROWS, countRows(
                "SELECT COUNT(*) FROM video_interaction_window WHERE vid", ownVids),
                "每个请求一个独立 actor，所以窗口行数等于请求数");
        assertEquals(0, countRows(
                "SELECT COUNT(*) FROM video_status_outbox WHERE aggregate_id", ownVids),
                "本片绝不产生 Outbox 事件（既无待处理也无已发送）");

        String report = renderReport(seed, singleAdmission, singlePostReturnRead,
                tenAdmission, tenPostReturnRead, replayAdmission);
        assertReportHasNoIdentifiers(report, allProbes);
        System.out.println(report);
    }

    // ------------------------------------------------------------------ phases

    /** 不计时的 warmup：独立 vid，避免与任何 oracle vid 混在一起。 */
    private void warmUp(int vid, List<Probe> allProbes) {
        for (int expected = 1; expected <= WARMUP_REQUESTS; expected++) {
            Probe probe = newProbe();
            allProbes.add(probe);
            VideoInteractionVO result = play(vid, probe);
            assertTrue(result.counted(), "warmup 请求必须是新计数");
            assertFalse(result.duplicate());
        }
        assertEquals((long) WARMUP_REQUESTS, playTimes(vid));
    }

    /**
     * 一次计时的新计数请求：admission 前打点 → 返回打点 → 立刻读 {@code video_status} 再打点。
     *
     * <p>第二个样本是 <b>post-return read</b>：起点在 {@code record()} 已经提交并返回<b>之后</b>，
     * 所以它不含 commit → 返回那段，<b>既不是</b> commit 耗时，<b>也不是</b> commit → 可见区间的上界。
     * 可见性不靠这个耗时证明，而靠下面那个确切值断言。</p>
     *
     * <p>断言在打点之后执行，避免断言开销污染样本。同步路径提交返回后必须立即可见，
     * 所以这里用确切值断言（{@code expectedCount}），<b>不</b>轮询等待。</p>
     */
    private TimedRequest timedCountedPlay(int vid, Probe probe, long expectedCount) {
        long start = System.nanoTime();
        VideoInteractionVO result = play(vid, probe);
        long returned = System.nanoTime();
        long observed = playTimes(vid);
        long readDone = System.nanoTime();

        assertTrue(result.counted(), "全新 visitor 的第一次请求必须计数");
        assertFalse(result.duplicate(), "全新 requestKey 不是重放");
        assertEquals(VideoInteractionVO.REASON_COUNTED, result.reason());
        assertEquals(expectedCount, observed, "同步提交返回后 video_status 必须立即可见");
        return new TimedRequest(returned - start, readDone - returned);
    }

    private VideoInteractionVO play(int vid, Probe probe) {
        return admissionService.record(VideoInteractionAction.PLAY, null, vid,
                probe.requestKey(), probe.visitor());
    }

    // ------------------------------------------------------------------ report

    private String renderReport(Seed seed,
                                List<Long> singleAdmission,
                                List<Long> singlePostReturnRead,
                                List<Long> tenAdmission,
                                List<Long> tenPostReturnRead,
                                List<Long> replayAdmission) {
        StringBuilder report = new StringBuilder(1024);
        report.append("=== T16-B0 同步准入基线（测试环境基线测量） ===\n");
        report.append("口径: 单 JVM / 串行 / 同步计数(async-enabled=false) / 非 HTTP（直接调用 admission 服务）"
                + " / 无 MQ / 无 listener·relay·scheduler / cleanup 关闭（未删除任何数据）"
                + " / admission 样本含 Redis 固定窗口准入 2 次脚本往返\n");
        report.append("不是容量上限、不是吞吐、不是 SLA、不是生产验收：固定小样本、单机单线程、"
                + "受 JVM/GC/连接池噪声影响\n");
        report.append("warmup: 每场景 ").append(WARMUP_REQUESTS)
                .append(" 条（各自独立 vid，已计入 oracle 但完全不计入分位数）\n");
        report.append("样本: single-video=").append(SINGLE_VIDEO_REQUESTS)
                .append(" | ten-videos=").append(TEN_VIDEO_COUNT * REQUESTS_PER_VIDEO)
                .append(" (").append(TEN_VIDEO_COUNT).append(" 个视频 x ").append(REQUESTS_PER_VIDEO)
                .append(") | replay=").append(REPLAY_REQUESTS).append('\n');
        report.append("口径说明: admission = 调用前 -> admission 返回（含两次 Redis 固定窗口准入脚本往返）；"
                + "post-return read = admission 返回 -> SELECT video_status 读回"
                + "（含取连接、网络往返、语句解析）\n");
        report.append("口径边界: commit 发生在 admission 返回之前，post-return read 不含 commit -> 返回 那一段，"
                + "所以它不是 commit 耗时，也不是 commit -> 可见区间的上界（真实区间严格更长）；"
                + "可见性由断言的确切值证明，不由耗时证明；本片不测异步端到端延迟\n");

        report.append(summaryLine("phase single-video admission", singleAdmission)).append('\n');
        report.append(summaryLine("phase single-video post-return read", singlePostReturnRead)).append('\n');
        report.append(summaryLine("phase ten-videos admission", tenAdmission)).append('\n');
        report.append(summaryLine("phase ten-videos post-return read", tenPostReturnRead)).append('\n');
        report.append(summaryLine("phase replay admission", replayAdmission)).append('\n');
        report.append("（replay 阶段只有 admission 样本：重放不写库，所以没有 post-return read 样本）\n");

        report.append("oracle: single=").append(SINGLE_VIDEO_REQUESTS)
                .append(" ten=").append(TEN_VIDEO_COUNT).append('x').append(REQUESTS_PER_VIDEO)
                .append(" warmup=").append(2).append('x').append(WARMUP_REQUESTS)
                .append(" requestRows=").append(EXPECTED_REQUEST_ROWS)
                .append(" countedRows=").append(EXPECTED_REQUEST_ROWS)
                .append(" suppressedRows=0 outboxRows=0")
                .append(" replays=").append(REPLAY_REQUESTS)
                .append("（重放后计数与窗口时间均未变）\n");
        report.append("seed left in place: users=1 videos=").append(seed.allVids().size())
                .append(" requestRows=").append(EXPECTED_REQUEST_ROWS)
                .append(" windowRows=").append(EXPECTED_REQUEST_ROWS)
                .append("；未删除任何 DB 行 / Redis key，未改动 dirty·gate·processed，未 purge 任何队列\n");
        return report.toString();
    }

    private static String summaryLine(String label, List<Long> samplesNanos) {
        long[] samples = new long[samplesNanos.size()];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = samplesNanos.get(i);
        }
        return VideoInteractionAdmissionBaselineSummary.of(samples).describe(label);
    }

    /**
     * 报告里不得出现任何标识或连接凭据：vid / requestKey / visitor / actorHash 一个都不能有，
     * 否则日志就不再是“只有耗时”的测量输出。
     */
    private void assertReportHasNoIdentifiers(String report, List<Probe> probes) {
        for (Probe probe : probes) {
            assertFalse(report.contains(probe.requestKey()), "报告不得包含 requestKey");
            assertFalse(report.contains(probe.visitor()), "报告不得包含 visitor 标识");
        }
        assertFalse(report.contains("vid="), "报告不得包含 vid");
        assertFalse(report.contains("jdbc:"), "报告不得包含连接串");
        assertFalse(report.contains("password"), "报告不得包含凭据字段");
    }

    // ------------------------------------------------------------------ seed / oracle helpers

    /** 本片所需的全部表：基础 schema + T15 迁移两组（缺表直接失败，不自动建表、不跳过）。 */
    private void requireBaselineTables() {
        requireTables(BASE_SCHEMA_TABLES, T15_MIGRATION_TABLES);
    }

    /**
     * 只读预检实现：按表的<b>来源分组</b>报缺失，所以错误信息不会把基础 schema 的缺表说成“去执行 T15 迁移”。
     *
     * @param baseSchemaTables   由 {@code database/feibijiubi.sql} 建立的表
     * @param t15MigrationTables 由 {@code t15-video-interaction-idempotency.sql} 建立的表
     * @throws IllegalStateException 任一表不存在（消息里点名缺表、归属来源与所需文件）
     */
    private void requireTables(List<String> baseSchemaTables, List<String> t15MigrationTables) {
        List<String> missingBaseSchema = missingTables(baseSchemaTables);
        List<String> missingT15Migration = missingTables(t15MigrationTables);
        if (missingBaseSchema.isEmpty() && missingT15Migration.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("T16-B0 基线片缺少必需的表：");
        if (!missingBaseSchema.isEmpty()) {
            message.append("基础 schema ").append(missingBaseSchema)
                    .append("（由 database/feibijiubi.sql 建立，T15 迁移不建这些表）");
            if (!missingT15Migration.isEmpty()) {
                message.append("；");
            }
        }
        if (!missingT15Migration.isEmpty()) {
            message.append("T15 交互表 ").append(missingT15Migration)
                    .append("（需执行 database/migrations/t15-video-interaction-idempotency.sql）");
        }
        message.append("；本测试不会自动建表或迁移，也不会以“跳过”代替失败");
        throw new IllegalStateException(message.toString());
    }

    /** 只读查询 {@code information_schema.tables}，返回不存在的表名（不改任何东西）。 */
    private List<String> missingTables(List<String> tables) {
        List<String> missing = new ArrayList<>();
        for (String table : tables) {
            Long present = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = DATABASE() AND table_name = ?", Long.class, table);
            if (present == null || present != 1L) {
                missing.add(table);
            }
        }
        return missing;
    }

    /**
     * 只新建本片自己的 user / video / video_status 行（不更新、不删除任何既有行）。
     *
     * <p>13 个视频 = 单视频场景 1 + 该场景 warmup 1 + 多视频场景 10 + 该场景 warmup 1。</p>
     */
    private Seed seedOwnVideos() {
        String username = "t16-b0-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')",
                username);
        Integer uid = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Integer.class, username);

        int singleVid = seedVideo(uid, "t16-b0-single");
        int singleWarmupVid = seedVideo(uid, "t16-b0-single-warmup");
        List<Integer> tenVids = new ArrayList<>(TEN_VIDEO_COUNT);
        for (int i = 0; i < TEN_VIDEO_COUNT; i++) {
            tenVids.add(seedVideo(uid, "t16-b0-multi-" + i));
        }
        int tenVideoWarmupVid = seedVideo(uid, "t16-b0-multi-warmup");

        return new Seed(singleVid, singleWarmupVid, List.copyOf(tenVids), tenVideoWarmupVid);
    }

    /** 已过审（status=1）且 duration 合法的视频 + 一行全 0 的 video_status。 */
    private int seedVideo(int uid, String title) {
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,"
                + "video_key,duration,status) "
                + "VALUES (?, ?, 'test', 'test', '', '', '', '', 12.5, 1)", uid, title);
        Integer vid = jdbc.queryForObject(
                "SELECT MAX(vid) FROM video WHERE uid = ?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times,share_times) VALUES (?, 0, 0)", vid);
        return vid;
    }

    /** 统计当前计数（同步路径直接落 {@code video_status}）。 */
    private long playTimes(int vid) {
        return jdbc.queryForObject(
                "SELECT play_times FROM video_status WHERE vid = ?", Long.class, vid);
    }

    /** 在给定 vid 集合内计行数：{@code countSqlEndingAtColumn} 必须以要过滤的列名结尾（vid / aggregate_id）。 */
    private int countRows(String countSqlEndingAtColumn, List<Integer> vids) {
        String sql = countSqlEndingAtColumn
                + " IN (" + String.join(",", Collections.nCopies(vids.size(), "?")) + ")";
        Integer count = jdbc.queryForObject(sql, Integer.class, vids.toArray());
        return count == null ? -1 : count;
    }

    /** 每个探针对应窗口行的 {@code last_counted_at}（CAST 成字符串，避免驱动时间类型差异）。 */
    private List<String> windowLastCountedAt(int vid, List<Probe> probes) {
        List<String> values = new ArrayList<>(probes.size());
        for (Probe probe : probes) {
            String actorHash = actorResolver.resolve(null, probe.visitor()).hash();
            values.add(jdbc.queryForObject(
                    "SELECT CAST(last_counted_at AS CHAR) FROM video_interaction_window "
                            + "WHERE actor_hash = ? AND vid = ? AND action = 'PLAY'",
                    String.class, actorHash, vid));
        }
        return values;
    }

    private void assertNoBeansOfType(Class<?>... types) {
        for (Class<?> type : types) {
            String[] names = context.getBeanNamesForType(type);
            assertEquals(0, names.length, "本基线片不得存在 " + type.getSimpleName()
                    + " bean，实际：" + Arrays.toString(names));
        }
    }

    /** 全新 requestKey（大小写敏感列，含随机 UUID 保证唯一）。 */
    private static Probe newProbe() {
        return new Probe("t16-b0-" + UUID.randomUUID(), UUID.randomUUID().toString());
    }

    /** 一次新请求：全新 canonical visitor UUID + 全新 requestKey，因此不会被窗口抑制或超限。 */
    private record Probe(String requestKey, String visitor) {
    }

    /** 一次计时结果（纳秒）：admission 用时，以及 admission 返回之后到 {@code video_status} 读回的用时。 */
    private record TimedRequest(long admissionNanos, long postReturnReadNanos) {
    }

    /** 本片自己 seed 的视频集合：单视频场景 / 其 warmup / 多视频场景 10 个 / 其 warmup。 */
    private record Seed(int singleVid,
                        int singleWarmupVid,
                        List<Integer> tenVids,
                        int tenVideoWarmupVid) {
        List<Integer> allVids() {
            List<Integer> vids = new ArrayList<>(tenVids.size() + 3);
            vids.add(singleVid);
            vids.add(singleWarmupVid);
            vids.addAll(tenVids);
            vids.add(tenVideoWarmupVid);
            return vids;
        }
    }
}
