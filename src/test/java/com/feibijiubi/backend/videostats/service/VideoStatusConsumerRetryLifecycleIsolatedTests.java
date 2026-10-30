package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.videostats.mq.VideoStatusConsumerRecoveryController;
import com.feibijiubi.backend.videostats.mq.RabbitConstants;
import com.feibijiubi.backend.infrastructure.redis.RedisKeyUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T08 真实隔离集成测试：RabbitMQ 三级 TTL、真实 listener stop/start、转发失败限速、
 * 合法重复投递只计一次、Redis 服务暂停 120 秒后的收敛。
 *
 * <p>只在 {@code -DvideoStatusIT=true} 时运行；复用
 * {@link VideoStatusIsolatedIntegrationTests.IsolationGuard}，连接固定隔离端口
 * (MySQL 13307 / Redis 16379 / RabbitMQ 15673)，不会接触正式环境。</p>
 *
 * <p><b>Redis 故障边界</b>：Redis 测试使用 {@code CLIENT PAUSE}，Redis 进程和 TCP 连接都保持存活，
 * 只是服务端暂停处理命令 120 秒后由测试 {@code CLIENT UNPAUSE} 恢复。这刻意<b>不是</b>杀进程/
 * 断连接的边界；不覆盖网络分区、进程重启或数据丢失。</p>
 *
 * <p>本类单次运行包含真实 120 秒等待（三级 TTL 约 125 秒 + Redis 暂停 120 秒），
 * 父任务应串行执行，不要与其它隔离 IT 并行。</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(
        classes = VideoStatusIsolatedIntegrationTests.TestApplication.class,
        properties = {
                "spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none",
                "app.video-status.consumer-recovery-delay-ms=600"
        })
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers =
        VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
// Start and stop a real listener in this class; a cached context would keep that
// consumer alive and steal messages from the next isolated IT class.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VideoStatusConsumerRetryLifecycleIsolatedTests {

    private static final List<String> TEST_QUEUES = List.of(
            RabbitConstants.MAIN_QUEUE,
            RabbitConstants.RETRY_QUEUE,
            RabbitConstants.RETRY_QUEUE_30S,
            RabbitConstants.RETRY_QUEUE_120S,
            RabbitConstants.DEAD_QUEUE);

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired VideoStatusConsumerRecoveryController recovery;

    RabbitAdmin admin;

    @BeforeEach
    void prepare() throws Exception {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource())
                .getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                            .startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"),
                    "Integration test must use the isolated MySQL port");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        admin = new RabbitAdmin(rabbit.getConnectionFactory());
        unpauseRedis();
        // A previous test may have triggered a scheduled stop/restart.  Wait for
        // that cycle to finish before taking over the container lifecycle.
        stopListenerWhenRecoveryIdle();
        purgeDedicatedQueues();
        MessageListenerContainer container = container();
        if (!container.isRunning()) {
            container.start();
        }
    }

    @AfterEach
    void restore() throws Exception {
        unpauseRedis();
        // Never race the recovery controller's asynchronous restart: it may still
        // have a stop/start scheduled from the forwarding-failure test.  Starting
        // the container from two threads corrupts the container's consumer set
        // ("A stopped container should not have consumers") and a late restart
        // can consume messages of a test that deliberately stopped the listener.
        stopListenerWhenRecoveryIdle();
        purgeDedicatedQueues();
        container().start();
    }

    /**
     * Stops the listener only while no recovery cycle is in flight.  The recovery
     * controller keeps its gate set until its restart task has completed, so a
     * false gate means no scheduled task can start the container behind our back.
     */
    private void stopListenerWhenRecoveryIdle() throws Exception {
        long end = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < end) {
            if (!recovery.isRecoveryInProgress()) {
                MessageListenerContainer container = container();
                if (container.isRunning()) {
                    container.stop();
                }
                if (!recovery.isRecoveryInProgress()) {
                    assertTrue(!container.isRunning(),
                            "listener must be stopped after recovery is idle");
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("consumer recovery did not become idle in 30 seconds");
    }

    @Test
    @Order(1)
    void legalDuplicateDeliveryIsCountedExactlyOnce() throws Exception {
        int vid = freshVideo();
        VideoStatusChangedEvent event = event(vid);

        rabbit.convertAndSend(RabbitConstants.MAIN_EXCHANGE,
                RabbitConstants.MAIN_ROUTING_KEY, event);
        await(() -> current(vid) == 1L && consumedRows(vid) == 1, 30);

        // 同 eventId/同指纹的合法消息重复投递，幂等吸收，不能再次累计。
        rabbit.convertAndSend(RabbitConstants.MAIN_EXCHANGE,
                RabbitConstants.MAIN_ROUTING_KEY, event);
        await(() -> mainQueueDepth() == 0, 30);
        Thread.sleep(1_000);

        assertEquals(1L, current(vid), "重复投递不得重复计数");
        assertEquals(1, consumedRows(vid), "重复投递不得新增消费事件");
        assertEquals(0, deadQueueDepth(), "合法重复消息不得进入 DLQ");
    }

    @Test
    @Order(2)
    void realListenerStopAndStartRecoveryResumesConsumption() throws Exception {
        MessageListenerContainer container = container();
        assertTrue(container.isRunning(), "真实 listener 必须先运行");

        assertTrue(recovery.requestRecovery("isolated stop/start recovery test"),
                "首次恢复请求必须被接受");
        await(() -> !container.isRunning(), 10);

        int vid = freshVideo();
        rabbit.convertAndSend(RabbitConstants.MAIN_EXCHANGE,
                RabbitConstants.MAIN_ROUTING_KEY, event(vid));
        Thread.sleep(200);
        assertTrue(mainQueueDepth() >= 1,
                "停止期间消息必须留在主队列，不能被丢弃");
        assertNullCurrent(vid);

        await(() -> container.isRunning(), 10);
        await(() -> current(vid) == 1L, 30);
        assertEquals(1, consumedRows(vid));
    }

    @Test
    @Order(3)
    void realForwardFailurePausesConsumerWithoutUnboundedHotLoop()
            throws Exception {
        // 真实 broker 故障：删除重试交换机，使 retry 转发成为不可路由发布。
        admin.deleteExchange(RabbitConstants.RETRY_EXCHANGE);
        try {
            MessageListenerContainer container = container();
            assertTrue(container.isRunning());
            int vid = freshVideo();
            // 负增量在 Redis 无基线时产生 NEGATIVE_RESULT -> RetryableMessageException。
            VideoStatusChangedEvent negative = new VideoStatusChangedEvent(
                    UUID.randomUUID().toString(), vid, VideoStatusEventType.PLAY,
                    -1L, -1D, LocalDateTime.now(), 2, null);
            rabbit.convertAndSend(RabbitConstants.MAIN_EXCHANGE,
                    RabbitConstants.MAIN_ROUTING_KEY, negative);

            await(() -> !container.isRunning(), 20);
            assertTrue(mainQueueDepth() <= 1,
                    "转发失败只能保留原消息，不能复制出多条");

            long end = System.nanoTime() + 3_000_000_000L;
            int starts = 0;
            boolean wasRunning = false;
            while (System.nanoTime() < end) {
                boolean running = container.isRunning();
                if (running && !wasRunning) {
                    starts++;
                }
                wasRunning = running;
                Thread.sleep(20);
            }
            // 600ms 恢复延迟下 3 秒最多约 5 个恢复周期；无界 requeue 会是成百上千次。
            assertTrue(starts <= 6,
                    "转发失败恢复次数必须有界，实际 start 次数=" + starts);
            assertTrue(mainQueueDepth() <= 1,
                    "热循环会造成消息堆积/复制");
        } finally {
            admin.declareExchange(new DirectExchange(
                    RabbitConstants.RETRY_EXCHANGE, true, false));
            admin.declareBinding(BindingBuilder.bind(new Queue(
                            RabbitConstants.RETRY_QUEUE))
                    .to(new DirectExchange(RabbitConstants.RETRY_EXCHANGE))
                    .with(RabbitConstants.RETRY_ROUTING_KEY));
            admin.declareBinding(BindingBuilder.bind(new Queue(
                            RabbitConstants.RETRY_QUEUE_30S))
                    .to(new DirectExchange(RabbitConstants.RETRY_EXCHANGE))
                    .with(RabbitConstants.RETRY_ROUTING_KEY_30S));
            admin.declareBinding(BindingBuilder.bind(new Queue(
                            RabbitConstants.RETRY_QUEUE_120S))
                    .to(new DirectExchange(RabbitConstants.RETRY_EXCHANGE))
                    .with(RabbitConstants.RETRY_ROUTING_KEY_120S));
        }
    }

    @Test
    @Order(4)
    void realRabbitMqTieredTtlsExpireIndependentlyWithoutHeadOfLineBlocking()
            throws Exception {
        assertFixedTtl(RabbitConstants.RETRY_QUEUE, 10_000);
        assertFixedTtl(RabbitConstants.RETRY_QUEUE_30S, 30_000);
        assertFixedTtl(RabbitConstants.RETRY_QUEUE_120S, 120_000);

        MessageListenerContainer container = container();
        // Take over the listener only after any scheduled stop/restart has finished,
        // otherwise a late recovery task could restart it during the TTL window.
        stopListenerWhenRecoveryIdle();
        assertTrue(!container.isRunning());
        purgeDedicatedQueues();

        long startedAt = System.nanoTime();
        rabbit.send(RabbitConstants.RETRY_EXCHANGE,
                RabbitConstants.RETRY_ROUTING_KEY, marker("tier-10s"));
        rabbit.send(RabbitConstants.RETRY_EXCHANGE,
                RabbitConstants.RETRY_ROUTING_KEY_30S, marker("tier-30s"));
        rabbit.send(RabbitConstants.RETRY_EXCHANGE,
                RabbitConstants.RETRY_ROUTING_KEY_120S, marker("tier-120s"));

        await(() -> queueDepth(RabbitConstants.RETRY_QUEUE) == 1
                && queueDepth(RabbitConstants.RETRY_QUEUE_30S) == 1
                && queueDepth(RabbitConstants.RETRY_QUEUE_120S) == 1, 10);

        sleepUntil(startedAt, 12_000);
        assertEquals(1, mainQueueDepth(), "10 秒级必须先回到主队列");
        assertEquals(0, queueDepth(RabbitConstants.RETRY_QUEUE));
        assertEquals(1, queueDepth(RabbitConstants.RETRY_QUEUE_30S));
        assertEquals(1, queueDepth(RabbitConstants.RETRY_QUEUE_120S));

        sleepUntil(startedAt, 34_000);
        assertEquals(2, mainQueueDepth(), "30 秒级随后回到主队列");
        assertEquals(0, queueDepth(RabbitConstants.RETRY_QUEUE_30S));
        assertEquals(1, queueDepth(RabbitConstants.RETRY_QUEUE_120S),
                "120 秒消息不能被 10/30 秒队列提前释放");

        sleepUntil(startedAt, 126_000);
        assertEquals(3, mainQueueDepth(), "120 秒级到期后回到主队列");
        assertEquals(0, queueDepth(RabbitConstants.RETRY_QUEUE_120S));
    }

    @Test
    @Order(5)
    void redisUnavailableFor120SecondsConvergesOrLeavesReplayableDlq()
            throws Exception {
        assertTrue(container().isRunning());
        int vid = freshVideo();

        // 服务端暂停处理命令 120 秒；连接/进程保持存活，不是断连边界。
        redis.execute((RedisCallback<Object>) connection -> connection.execute(
                "CLIENT",
                "PAUSE".getBytes(StandardCharsets.UTF_8),
                "120000".getBytes(StandardCharsets.UTF_8)));
        try {
            rabbit.convertAndSend(RabbitConstants.MAIN_EXCHANGE,
                    RabbitConstants.MAIN_ROUTING_KEY, event(vid));
            Thread.sleep(120_000);
        } finally {
            unpauseRedis();
        }

        // 恢复后必须收敛（或按重试预算进入可重放 DLQ），不能静默消失。
        // 不能只等 current 键“出现”：重建初始化会先写入基线 0，事件应用完成前
        // 该键已经存在，过早把 0 当成收敛会掩盖仍在处理的恢复流程。
        await(() -> {
            Long value = currentNullable(vid);
            return (value != null && value == 1L) || deadQueueDepth() > 0;
        }, 240);
        long current = currentNullable(vid) == null ? 0L : currentNullable(vid);
        assertTrue(current == 1L || deadQueueDepth() == 1,
                "Redis 恢复后事件必须安全收敛或进入可重放 DLQ，实际 current="
                        + current + ", dlq=" + deadQueueDepth());
        if (current == 1L) {
            assertEquals(1, consumedRows(vid));
            assertEquals(0, deadQueueDepth(),
                    "正常收敛时不得同时产生 DLQ 副本");
        }
    }

    /**
     * RabbitMQ 的 passive declare 不返回参数，因此用“同参数重复 declare”验证服务端参数：
     * durable 队列参数不一致时 broker 会返回 PRECONDITION_FAILED，声明成功即证明
     * TTL/DLX/routing-key 与期望完全一致。
     */
    private void assertFixedTtl(String queue, int ttlMs) {
        Map<String, Object> expected = Map.of(
                "x-message-ttl", ttlMs,
                "x-dead-letter-exchange", RabbitConstants.MAIN_EXCHANGE,
                "x-dead-letter-routing-key", RabbitConstants.MAIN_ROUTING_KEY);
        rabbit.execute(channel -> {
            channel.queueDeclare(queue, true, false, false, expected);
            return null;
        });
    }

    private Message marker(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8));
    }

    private void sleepUntil(long startedAt, long millis) throws Exception {
        long remaining = millis - (System.nanoTime() - startedAt) / 1_000_000;
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }

    private VideoStatusChangedEvent event(int vid) {
        return new VideoStatusChangedEvent(UUID.randomUUID().toString(), vid,
                VideoStatusEventType.PLAY, 1L, 1D, LocalDateTime.now(), 2, null);
    }

    private int freshVideo() {
        String username = "it-t08-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) "
                + "VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject(
                "SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,"
                        + "video_url,video_key) VALUES (?, 'isolated-test', 'test', "
                        + "'test', '', '', '', '')", uid);
        int vid = jdbc.queryForObject(
                "SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times) VALUES (?,0)", vid);
        return vid;
    }

    private MessageListenerContainer container() {
        MessageListenerContainer container = registry.getListenerContainer(
                RabbitConstants.CONSUMER_LISTENER_ID);
        assertNotNull(container, "真实 listener 容器必须存在");
        return container;
    }

    private long current(int vid) {
        Long value = currentNullable(vid);
        return value == null ? -1L : value;
    }

    private Long currentNullable(int vid) {
        Object raw = redis.opsForHash().get(
                RedisKeyUtils.videoStatus(vid), "playTimes");
        return raw == null ? null : Long.valueOf(raw.toString());
    }

    private void assertNullCurrent(int vid) {
        assertNull(currentNullable(vid),
                "listener 停止期间不得应用事件");
    }

    private int consumedRows(int vid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event "
                + "WHERE vid=?", Integer.class, vid);
    }

    private int mainQueueDepth() {
        return queueDepth(RabbitConstants.MAIN_QUEUE);
    }

    private int deadQueueDepth() {
        return queueDepth(RabbitConstants.DEAD_QUEUE);
    }

    private int queueDepth(String queue) {
        var info = admin.getQueueInfo(queue);
        assertNotNull(info, "隔离测试队列必须存在: " + queue);
        return info.getMessageCount();
    }

    private void purgeDedicatedQueues() {
        for (String queue : TEST_QUEUES) {
            admin.purgeQueue(queue, false);
        }
    }

    private void unpauseRedis() {
        try {
            redis.execute((RedisCallback<Object>) connection ->
                    connection.execute("CLIENT",
                            "UNPAUSE".getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException ignored) {
            // Redis 未暂停或连接正在恢复时无需失败；测试主体会重新断言。
        }
    }

    private void await(BooleanSupplier condition, int seconds) throws Exception {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        assertTrue(condition.getAsBoolean(),
                "condition did not converge in " + seconds + " seconds");
    }
}
