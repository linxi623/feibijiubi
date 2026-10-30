package com.feibijiubi.backend.videostats.service;

import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.videostats.mapper.VideoStatusConsumedEventMapper;
import com.feibijiubi.backend.videostats.mapper.VideoStatusOutboxMapper;
import com.feibijiubi.backend.videostats.mq.VideoStatusBatchFlushScheduler;
import com.feibijiubi.backend.videostats.mq.VideoStatusOutboxRelay;
import com.feibijiubi.backend.videostats.mq.RabbitConstants;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * T07 真实回写故障验收：<b>confirm 已 ACK、markSent 回写失败</b>时消息既不丢也不判失败。
 *
 * <p>与同目录其它用例的分工（避免重复验收）：</p>
 * <ul>
 *   <li>{@link VideoStatusOutboxRelayTests} 是纯单元测试：mock 掉 mapper/publisher，
 *       只验分类分支；</li>
 *   <li>{@link VideoStatusOutboxLeaseTests} 只验 SQL/CAS 语义（markConfirmedPending /
 *       markInvalidFailed / recoverExpiredSending），不发 MQ；</li>
 *   <li>本类只做一件它们都做不到的事：在<b>真实 RabbitMQ（真实 publisher confirm）、
 *       真实 MySQL、真实消费者、真实 relay/claim/mapper</b> 上注入回写故障，
 *       证明“ACK 已发生但回写失败”不会丢消息、不会误判 FAILED、不会二次计数。</li>
 * </ul>
 *
 * <p>本类<b>不</b>重复验证租约 CAS 接管（见 {@code VideoStatusOutboxLeaseTests}），
 * 也<b>不</b>实现 T10 的处置逻辑：这里出现的 FAILED 只来自 relay 对历史行的正常处理。</p>
 *
 * <p>装配与门控：直接复用 {@link VideoStatusIsolatedIntegrationTests.TestApplication}
 * （真实 DataSource/Redis/RabbitMQ/Mapper/消费者/relay/落库调度）与
 * {@link VideoStatusIsolatedIntegrationTests.IsolationGuard}（启动前拒绝非隔离端点）。
 * 不读 {@code application.yml}，不启停容器，不碰 localhost:6379 / 生产端口。</p>
 *
 * <p>故障注入范围：</p>
 * <ul>
 *   <li>只 spy <b>真实的</b> {@link VideoStatusOutboxMapper}，且只在 <b>本用例自己的 eventId</b>
 *       上前 N 次抛 {@link DataAccessResourceFailureException}；历史行的 markSent /
 *       markConfirmedPending 一律走真实 SQL（注入器会记录命中的 eventId，用例结尾断言
 *       只命中自己那一条）。</li>
 *   <li>不新建 eventId、不改 payload、不改 status 来“掩盖”重复：整个用例只创建 1 条 Outbox 行，
 *       断言 DISTINCT event_id = 1、消费行 = 1、最终基线 = 1。</li>
 *   <li>唯一一处测试自己写的 SQL 是“把自己那行的 {@code sending_at} 置为过期”，
 *       这是租约过期的故障推进，仍然只作用于自己的 event_id。</li>
 * </ul>
 *
 * <p>时钟说明（隔离环境的事实，不是断言依据）：隔离 MySQL 跑在 UTC，JVM 在 +08:00。
 * {@code markConfirmedPending} 的 {@code next_retry_at} 由 JVM 时钟写入、claim 也用 JVM 时钟比较，
 * 所以“等 1 秒退避到期”是等 JVM 时间；{@code recoverExpiredSending} 用数据库 {@code NOW(3)+1s}
 * 写入，在同一台机器上比 JVM 时间早 8 小时，因此恢复后的行对本 JVM 立即可领取。
 * 两种情形都用 {@code await ≤ 5s} 的轮询覆盖，不 sleep 固定时长、不依赖时区一致。</p>
 *
 * <p>运行（只跑本类，必须显式打开 IT 门控）：
 * {@code ./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusOutboxReliabilityIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusIsolatedIntegrationTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusIsolatedIntegrationTests.IsolationGuard.class)
// This class reuses the IT application context, which starts the real consumer; close
// it after the class so the consumer cannot steal messages from a later test class.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VideoStatusOutboxReliabilityIsolatedTests {

    /** 等“下一轮退避到期”的上限：1 秒退避 + 调度余量。 */
    private static final Duration RETRY_DELAY_BUDGET = Duration.ofSeconds(5);

    /** 等第一轮 relay（可能还要处理少量历史 pending 行）的上限。 */
    private static final Duration FIRST_ROUND_BUDGET = Duration.ofSeconds(30);

    private static final String REASON_CONFIRM_WRITEBACK_FAILED = "CONFIRM_WRITEBACK_FAILED";

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired VideoStatusService stats;
    @Autowired VideoStatusBatchFlushScheduler scheduler;
    @Autowired VideoStatusOutboxRelay relay;
    @Autowired VideoStatusConsumedEventMapper consumedMapper;
    @Autowired VideoStatusProperties properties;
    @Autowired TransactionTemplate tx;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired SqlSessionTemplate sqlSessionTemplate;

    /**
     * 真实 MyBatis mapper（JDK 代理）的 spy：默认调用真实 SQL，只在目标 eventId 上
     * 注入前 N 次回写异常。故障注入与“真实 ACK”因此同时成立。
     */
    @MockitoSpyBean VideoStatusOutboxMapper outboxMapper;

    /**
     * spy 背后“未注入故障”的调用必须走<b>真实 SQL</b>，且不能是 spy 自己。
     *
     * <p>为什么不用 {@code invocation.callRealMethod()}：{@code @MockitoSpyBean} 作用在
     * MyBatis 的 JDK 动态代理（接口型 bean）上时，接口方法是抽象的，Mockito 无法“调用真实方法”
     * （{@code Cannot call abstract real method on java object!}），之前那样写会让
     * markConfirmedPending 也“失败”，从而把用例变成假故障。这里用 mybatis-spring 的
     * {@link SqlSessionTemplate} 现取一个同配置的真实 mapper 代理，显式转发未注入故障的调用：
     * 语义与 {@code callRealMethod} 等价，历史行也仍然走真实 SQL。</p>
     */
    private VideoStatusOutboxMapper realOutboxMapper() {
        return sqlSessionTemplate.getMapper(VideoStatusOutboxMapper.class);
    }

    /** 本用例唯一的事件：Outbox 里只允许存在这一条自己的行。 */
    private String eventId;

    private final AtomicInteger markSentFailuresLeft = new AtomicInteger();
    private final AtomicInteger markConfirmedPendingFailuresLeft = new AtomicInteger();
    private final AtomicInteger markSentFailuresApplied = new AtomicInteger();
    private final AtomicInteger markConfirmedPendingFailuresApplied = new AtomicInteger();
    /** 注入器实际命中过的 eventId：必须只有本用例自己那一条。 */
    private final Set<String> faultedEventIds = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------ seeding

    @BeforeEach
    void seedOwnVideoAndOutbox() {
        assertIsolatedEndpoints();
        // 上一次中断运行可能残留消息；只清自己的三条队列，不碰库里的历史行。
        RabbitAdmin admin = new RabbitAdmin(rabbit.getConnectionFactory());
        for (String queue : List.of(RabbitConstants.MAIN_QUEUE, RabbitConstants.RETRY_QUEUE,
                RabbitConstants.RETRY_QUEUE_30S, RabbitConstants.RETRY_QUEUE_120S,
                RabbitConstants.DEAD_QUEUE)) {
            admin.purgeQueue(queue, false);
        }
        assertTrue(properties.isAsyncEnabled(), "隔离 IT 必须以 async-enabled=true 启动");
        assertTrue(properties.isOutboxRelayEnabled(), "隔离 IT 必须以 outbox-relay-enabled=true 启动");
        assertTrue(properties.isSchedulingEnabled(), "隔离 IT 必须以 scheduling-enabled=true 启动");

        // 真实消费者的监听容器来自 IT 配置（spring.rabbitmq.listener.simple.auto-startup 默认 true）。
        boolean consumerRunning = listenerRegistry.getListenerContainers().stream()
                .anyMatch(container -> container.isRunning());
        assertTrue(consumerRunning, "真实消费者必须已自动启动，否则 ACK 后无人消费");

        int vid = freshVideo();
        VideoStatusChangedEvent event = createEvent(vid);
        eventId = event.eventId();

        OutboxRow created = outboxRow(eventId);
        assertEquals(0, created.status(), "新事件必须先停留在 PENDING");
        assertEquals(0, created.retryCount(), "新事件 retry_count=0");
        assertEquals(0, created.leaseRecoveryCount(), "新事件 lease_recovery_count=0");
        assertEquals(1, outboxRows(vid), "本用例只创建 1 条 Outbox");
        assertEquals(0, baseline(vid), "异步入口不得直接改 DB 基线");
    }

    // --------------------------------------------------- 1) markSent 前两次失败

    /**
     * 真实 publish 已经拿到 RabbitMQ confirm ACK，但 {@code markSent} 前两次抛
     * {@link DataAccessResourceFailureException}：
     * <ol>
     *   <li>第一次：relay 改为 {@code markConfirmedPending} → 回 PENDING、{@code retry_count} 不变、
     *       不判 FAILED；同时真实消费者已经消费了这条消息（ACK 确实发生过）；</li>
     *   <li>等 nextRetryAt 到期（{@code await ≤ 5s}）后重投：第二次 markSent 再失败 →
     *       再次回到 PENDING，消费行仍然只有 1 行、Redis 仍然只 +1；</li>
     *   <li>第三次真实 markSent 成功 → SENT；</li>
     *   <li>真实落库调度把唯一一行消费事件推进到 FLUSHED，基线/Redis 都是 1：
     *       3 次投递、唯一 eventId、只计一次。</li>
     * </ol>
     */
    @Test
    void confirmedPublishSurvivesTwoMarkSentFailuresAndCountsOnce() throws Exception {
        int vid = vidOf(eventId);
        installWritebackFaults(2, 0);

        // ---------- 第一次：ACK 成功、markSent 失败 → markConfirmedPending ----------
        await(() -> {
            relay.relay();
            return markSentFailuresApplied.get() >= 1;
        }, FIRST_ROUND_BUDGET, "relay 未在预算内对目标事件执行 markSent");

        OutboxRow firstFailure = outboxRow(eventId);
        assertEquals(0, firstFailure.status(),
                "confirm 已 ACK 但 markSent 失败时必须退回 PENDING 等待重投，不能判 FAILED");
        assertEquals(0, firstFailure.retryCount(),
                "confirm 之后的回写失败不是发布失败，不得增加 retry_count");
        assertEquals(0, firstFailure.leaseRecoveryCount(), "这里不应发生租约恢复");
        assertNull(firstFailure.leaseToken(), "退回 PENDING 必须清空租约，旧 owner 不能再写");
        assertNull(firstFailure.sendingAt());
        assertNotNull(firstFailure.nextRetryAt(), "必须安排下一次重投时间");
        assertTrue(firstFailure.lastError().startsWith(REASON_CONFIRM_WRITEBACK_FAILED),
                "last_error 只写 reason code + 异常类名: " + firstFailure.lastError());

        // 真实 ACK 的证据：消息已到达 Broker 并被真实消费者处理（Redis 已 +1、消费行已登记）。
        await(() -> consumedStatus(eventId) != null && consumedStatus(eventId) == 1,
                RETRY_DELAY_BUDGET,
                "真实消费者未在预算内消费这条已 ACK 的消息（说明 ACK 并未真正发生）");
        assertEquals(1, current(vid), "真实消费必须让 Redis 只 +1");
        assertEquals(0, baseline(vid), "落库调度还没跑，DB 基线仍为 0");

        // ---------- 第二次：等退避到期后重投，markSent 再失败 ----------
        await(() -> {
            relay.relay();
            return markSentFailuresApplied.get() >= 2;
        }, RETRY_DELAY_BUDGET, "退避到期后 relay 未在 5 秒内重投并再次触发注入的 markSent 失败");

        OutboxRow secondFailure = outboxRow(eventId);
        assertEquals(0, secondFailure.status(), "第二次回写失败仍只能退回 PENDING");
        assertEquals(0, secondFailure.retryCount(), "两次回写失败都不得消耗发布重试预算");
        assertEquals(0, secondFailure.leaseRecoveryCount());
        assertEquals(1, consumedRows(eventId), "同一 eventId 的重复投递不得产生第二条消费行");
        assertEquals(1, current(vid), "重复投递不得二次计数 Redis");

        // ---------- 第三次：真实 markSent 成功 ----------
        await(() -> {
            relay.relay();
            return outboxRow(eventId).status() == 2;
        }, RETRY_DELAY_BUDGET, "第三次投递未在 5 秒内把事件推进到 SENT");

        OutboxRow sent = outboxRow(eventId);
        assertEquals(2, sent.status(), "第三次真实 markSent 必须落成 SENT");
        assertEquals(0, sent.retryCount(), "整个过程 retry_count 必须保持 0");
        assertEquals(0, sent.leaseRecoveryCount(), "整个过程不应触发租约恢复");
        assertNotNull(sent.sentAt());
        assertNull(sent.leaseToken());
        assertNull(sent.lastError(), "markSent 成功必须清空 last_error");

        // 投递次数 ≥ 2 但只计一次：每次 publish 前都先 markSent，因此调用数就是投递次数。
        verify(outboxMapper, times(3)).markSent(eq(eventId), anyString(), any());
        verify(outboxMapper, times(2)).markConfirmedPending(eq(eventId), anyString(), any(), anyString());

        // ---------- 真实落库：唯一消费行 FLUSHED，基线/Redis 都是 1 ----------
        await(() -> {
            scheduler.flushDirtyVideos();
            return baseline(vid) == 1;
        }, Duration.ofSeconds(10), "真实落库调度未把唯一事件推进到 DB");

        assertEquals(1, baseline(vid), "3 次投递只能落库一次");
        assertEquals(1, current(vid));
        assertEquals(2, consumedStatus(eventId), "唯一消费行必须 FLUSHED");
        assertSingleEventCountedOnce(vid);
        assertOnlyOwnEventIdWasFaulted();
        System.out.printf(Locale.ROOT,
                "T07 markSent-failure IT vid=%d eventId=%s deliveries=%d retryCount=%d "
                        + "leaseRecovery=%d baseline=%d%n",
                vid, eventId, markSentFailuresApplied.get() + 1,
                sent.retryCount(), sent.leaseRecoveryCount(), baseline(vid));
    }

    // --------------------------------------- 2) 两次回写都失败 → SENDING → 租约恢复

    /**
     * {@code markSent} 与 {@code markConfirmedPending} 都失败：relay 必须保留 SENDING
     * （既不改状态也不判 FAILED），消息已经在 Broker 上（真实消费者已消费）。
     *
     * <p>随后<b>只</b>把本用例自己那行的 {@code sending_at} 置为过期（故障推进，不动别人的行），
     * 交给真实 {@code recoverExpiredSending} + 真实 claim 接管：仍然只投递一次成功、
     * 唯一 eventId、最终一次 SENT / FLUSHED。</p>
     */
    @Test
    void doubleWritebackFailureKeepsSendingThenRealLeaseRecoverySendsOnce() throws Exception {
        int vid = vidOf(eventId);
        installWritebackFaults(1, 1);

        await(() -> {
            relay.relay();
            return markSentFailuresApplied.get() >= 1
                    && markConfirmedPendingFailuresApplied.get() >= 1;
        }, FIRST_ROUND_BUDGET, "relay 未在预算内同时触发两次回写故障");

        OutboxRow sending = outboxRow(eventId);
        assertEquals(1, sending.status(),
                "两次回写都失败必须保留 SENDING，等租约恢复，不得改状态也不得判 FAILED");
        assertEquals(0, sending.retryCount(), "回写失败不得增加 retry_count");
        assertEquals(0, sending.leaseRecoveryCount(), "此时还没发生租约恢复");
        assertNotNull(sending.leaseToken(), "SENDING 必须保留自己的租约 token");
        assertNotNull(sending.sendingAt());

        // ACK 已经发生：真实消费者已经处理过这条消息（否则 SENDING 里的消息就丢了）。
        await(() -> consumedStatus(eventId) != null && consumedStatus(eventId) == 1,
                RETRY_DELAY_BUDGET, "真实消费者未消费这条已 ACK 的消息");
        assertEquals(1, current(vid));

        // 只推进自己的 sending_at：让真实 recoverExpiredSending 命中本行。
        LocalDateTime expiredSendingAt = LocalDateTime.now()
                .minusSeconds(properties.getOutboxLeaseSeconds() + 600L);
        assertEquals(1, jdbc.update(
                        "UPDATE video_status_outbox SET sending_at = ? WHERE event_id = ? AND status = 1",
                        expiredSendingAt, eventId),
                "只允许更新本用例自己那一条 SENDING 行");

        // 真实租约恢复 + 真实 claim + 真实 markSent（故障注入已用尽）。
        await(() -> {
            relay.relay();
            return outboxRow(eventId).status() == 2;
        }, RETRY_DELAY_BUDGET,
                "租约恢复后未在 5 秒内重新投递成功（nextRetryAt 用数据库时钟写入）");

        OutboxRow sent = outboxRow(eventId);
        assertEquals(2, sent.status(), "租约恢复后必须能重新投递到 SENT");
        assertEquals(1, sent.leaseRecoveryCount(), "过期租约必须由真实 recoverExpiredSending 记 1 次");
        assertEquals(0, sent.retryCount(), "租约恢复不是发布失败，不得增加 retry_count");
        assertNotNull(sent.sentAt());
        assertNull(sent.leaseToken());
        assertNull(sent.lastError());

        // 两次投递（故障一次 + 恢复后成功一次），唯一 eventId 只计一次。
        verify(outboxMapper, times(2)).markSent(eq(eventId), anyString(), any());
        verify(outboxMapper, times(1)).markConfirmedPending(eq(eventId), anyString(), any(), anyString());

        await(() -> {
            scheduler.flushDirtyVideos();
            return baseline(vid) == 1;
        }, Duration.ofSeconds(10), "真实落库调度未把唯一事件推进到 DB");

        assertEquals(1, baseline(vid), "两次投递只能落库一次");
        assertEquals(1, current(vid));
        assertEquals(2, consumedStatus(eventId), "唯一消费行必须 FLUSHED");
        assertSingleEventCountedOnce(vid);
        assertOnlyOwnEventIdWasFaulted();
        System.out.printf(Locale.ROOT,
                "T07 double-writeback-failure IT vid=%d eventId=%s deliveries=2 retryCount=%d "
                        + "leaseRecovery=%d baseline=%d%n",
                vid, eventId, sent.retryCount(), sent.leaseRecoveryCount(), baseline(vid));
    }

    // --------------------------------------------------------------- fault injection

    /**
     * 只在<b>本用例自己的 eventId</b> 上注入前 N 次回写失败；其余调用（含所有历史行）
     * 走真实 SQL。命中过的 eventId 会被记录，用例结尾断言集合里只有自己那一条。
     */
    private void installWritebackFaults(int markSentFailures, int markConfirmedPendingFailures) {
        markSentFailuresLeft.set(markSentFailures);
        markConfirmedPendingFailuresLeft.set(markConfirmedPendingFailures);

        doAnswer(call -> {
            String target = call.getArgument(0);
            if (eventId.equals(target) && consumeOne(markSentFailuresLeft)) {
                faultedEventIds.add(target);
                markSentFailuresApplied.incrementAndGet();
                throw new DataAccessResourceFailureException("injected markSent failure");
            }
            return realOutboxMapper().markSent(
                    call.getArgument(0),
                    call.getArgument(1),
                    call.getArgument(2));
        }).when(outboxMapper).markSent(anyString(), anyString(), any());

        doAnswer(call -> {
            String target = call.getArgument(0);
            if (eventId.equals(target) && consumeOne(markConfirmedPendingFailuresLeft)) {
                faultedEventIds.add(target);
                markConfirmedPendingFailuresApplied.incrementAndGet();
                throw new DataAccessResourceFailureException(
                        "injected markConfirmedPending failure");
            }
            return realOutboxMapper().markConfirmedPending(
                    call.getArgument(0),
                    call.getArgument(1),
                    call.getArgument(2),
                    call.getArgument(3));
        }).when(outboxMapper).markConfirmedPending(anyString(), anyString(), any(), anyString());
    }

    /** 预算 > 0 时原子地减一并返回 true；预算耗尽后永不再注入。 */
    private static boolean consumeOne(AtomicInteger remaining) {
        while (true) {
            int current = remaining.get();
            if (current <= 0) {
                return false;
            }
            if (remaining.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    // ------------------------------------------------------------------ assertions

    /** 唯一 eventId、唯一消费行、只计一次：不靠新建 eventId 掩盖重复。 */
    private void assertSingleEventCountedOnce(int vid) {
        // 真实 mapper 读回唯一消费行（不是只看 SQL count）。
        var consumed = consumedMapper.selectByEventId(eventId);
        assertNotNull(consumed, "唯一消费行必须能被真实 mapper 读回");
        assertEquals(vid, consumed.getVid());
        assertEquals(2, consumed.getProcessStatus(), "唯一消费行必须是 FLUSHED");
        assertNotNull(consumed.getFlushedAt());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_outbox WHERE aggregate_id=?", Integer.class, vid),
                "整个用例只允许有 1 条 Outbox 行");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT event_id) FROM video_status_outbox WHERE aggregate_id=?",
                Integer.class, vid), "不得新建 eventId");
        assertEquals(1, consumedRows(eventId), "同一 eventId 只允许 1 条消费行");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT event_id) FROM video_status_consumed_event WHERE vid=?",
                Integer.class, vid), "同一 vid 不得出现第二个 eventId");
        assertEquals(1, baseline(vid), "唯一事件只落库一次");
        assertEquals(1, current(vid), "唯一事件在 Redis 只 +1 一次");
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(
                        RedisKeyUtils.dirtyVideo(), String.valueOf(vid))),
                "落库完成后本 vid 不得残留 dirty");
    }

    private void assertOnlyOwnEventIdWasFaulted() {
        assertEquals(Set.of(eventId), faultedEventIds,
                "故障注入只能命中本用例自己的 eventId，历史行必须走真实 SQL");
        assertEquals(0, markSentFailuresLeft.get());
        assertEquals(0, markConfirmedPendingFailuresLeft.get());
        // 本类不验证租约 CAS 接管（见 VideoStatusOutboxLeaseTests），也不该产生 FAILED。
        assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM video_status_outbox WHERE event_id=? AND status=3",
                        Integer.class, eventId),
                "本用例的事件不允许被任何路径判成 FAILED");
        verify(outboxMapper, never()).markFailed(eq(eventId), anyString(), anyString());
        verify(outboxMapper, never()).markInvalidFailed(eq(eventId), anyString(), anyString());
    }

    private void assertIsolatedEndpoints() {
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                            .startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"),
                    "本类只允许跑在隔离 MySQL 上");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // --------------------------------------------------------------------- helpers

    private int freshVideo() {
        String username = "t07-writeback-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')",
                username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 't07-writeback', 'test', 'test', '', '', '', '')", uid);
        int newVid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times) VALUES (?,0)", newVid);
        return newVid;
    }

    /** 真实业务入口：事务内 {@code createEvent} 写 Outbox（不直接改 Redis/DB）。 */
    private VideoStatusChangedEvent createEvent(int vid) {
        VideoStatusChangedEvent[] created = new VideoStatusChangedEvent[1];
        tx.executeWithoutResult(session ->
                created[0] = stats.createEvent(vid, VideoStatusEventType.PLAY, 1));
        assertNotNull(created[0]);
        return created[0];
    }

    private int vidOf(String forEventId) {
        Integer vid = jdbc.queryForObject(
                "SELECT aggregate_id FROM video_status_outbox WHERE event_id=?",
                Integer.class, forEventId);
        assertNotNull(vid, "seeded outbox row must exist: " + forEventId);
        return vid;
    }

    private OutboxRow outboxRow(String forEventId) {
        return jdbc.queryForObject(
                "SELECT status, retry_count, lease_recovery_count, next_retry_at, sending_at, "
                        + "lease_token, last_error, sent_at FROM video_status_outbox WHERE event_id = ?",
                (rs, index) -> new OutboxRow(
                        rs.getInt("status"),
                        rs.getInt("retry_count"),
                        rs.getInt("lease_recovery_count"),
                        rs.getObject("next_retry_at", LocalDateTime.class),
                        rs.getObject("sending_at", LocalDateTime.class),
                        rs.getString("lease_token"),
                        rs.getString("last_error"),
                        rs.getObject("sent_at", LocalDateTime.class)),
                forEventId);
    }

    private int outboxRows(int forVid) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_outbox WHERE aggregate_id=?",
                Integer.class, forVid);
    }

    /** 消费行数：按 eventId 精确计数，避免把同 vid 的其它行算进来。 */
    private int consumedRows(String forEventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM video_status_consumed_event WHERE event_id=?",
                Integer.class, forEventId);
    }

    private Integer consumedStatus(String forEventId) {
        List<Integer> statuses = jdbc.queryForList(
                "SELECT process_status FROM video_status_consumed_event WHERE event_id=?",
                Integer.class, forEventId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private long baseline(int vid) {
        return jdbc.queryForObject("SELECT play_times FROM video_status WHERE vid=?", Long.class, vid);
    }

    private long current(int vid) {
        Object value = redis.opsForHash().get(RedisKeyUtils.videoStatus(vid), "playTimes");
        return Long.parseLong((String) Objects.requireNonNull(value, "Redis current 必须已建立"));
    }

    private void await(BooleanSupplier condition, Duration timeout, String message) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    /** 一行 Outbox 的只读快照。 */
    private record OutboxRow(
            int status,
            int retryCount,
            int leaseRecoveryCount,
            LocalDateTime nextRetryAt,
            LocalDateTime sendingAt,
            String leaseToken,
            String lastError,
            LocalDateTime sentAt) {
    }
}
