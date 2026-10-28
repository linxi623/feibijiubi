package com.feibijiubi.backend.service.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.config.RedisScriptConfig;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.enums.VideoStatusConsumeProcessStatus;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusRebuildServiceImpl;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusRebuildSnapshotServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusRebuildService;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusVidMutex;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * T12 规模测量（opt-in，默认跳过）：0 / 1,000 / 100,000 条重建候选下的冷缓存重建。
 *
 * <p><b>默认不跑</b>：只有显式 {@code -DvideoStatusScale=true} 才启用；普通
 * {@code -Dtest=*Tests} 回归把它记为 skipped，opt-in 的 {@code -DvideoStatusIT=true}
 * 组（按类名过滤）也不会包含它。它只使用 {@code compose.test.yaml} 的
 * MySQL 13307 / Redis 16379 / RabbitMQ 15673，且启动前用 {@link IsolationGuard}
 * 拒绝任何非隔离端点覆盖。</p>
 *
 * <p>测量对象是真实的 {@code VideoStatusRebuildService#ensureInitialized(vid)}：
 * 真实 vid 锁、真实无 TTL 门闩、真实 MySQL keyset 分页、真实 Redis pipeline EXISTS。
 * 每个规模重复 {@value #REPETITIONS} 次冷启动（每次只删本测试 vid 的 current，
 * 门闩与 processed 证据保持真实状态），每次都用<b>预设 oracle</b> 断言
 * {@code playTimes = 基线 100 + 候选数}，并交叉核对 DB 行数、去重 eventId 数、
 * {@code SUM(delta)} 与其它七个计数为 0 —— 既不漏读也不重复计数。</p>
 *
 * <p><b>指标口径（务必按此理解，不要外推）</b>：</p>
 * <ul>
 *   <li><b>候选内存上界</b>：不是测量值，而是代码上界
 *       {@code rebuild-max-candidates + 1} 条候选（默认 100000，见
 *       {@link VideoStatusProperties#getRebuildMaxCandidates()}）；超过即失败且不返回截断快照。</li>
 *   <li><b>heap</b>：{@code System.gc()} 后的已用堆 + 各 heap 池
 *       {@code resetPeakUsage()} 之后读取的池峰值之和。池峰值之和只是<b>上界近似</b>
 *       （各池峰值不在同一时刻），而且包含候选以外的临时对象；它<b>不是</b>
 *       “候选对象的 retained size”，也不能被当成精确瞬时峰值。</li>
 *   <li><b>pipeline 调用/命令数</b>：由 {@link RedisUtils} 上的 spy 统计的
 *       <b>逻辑</b>调用次数与 EXISTS 命令数，不是物理网络往返次数
 *       （Lettuce 连接复用、批大小、TCP 分包都会影响真实 RTT）。</li>
 *   <li><b>重建耗时 / 门闩持有</b>：{@code System.nanoTime()} 计量的进程内时钟。
 *       门闩窗口 = 门闩写入调用返回 → 释放脚本调用返回（释放发生在 {@code finally}）。</li>
 *   <li><b>P95</b>：只有 {@value #REPETITIONS} 个样本，任何 P95 都只是诊断；
 *       输出里显式标注 {@code p95=n/a}，不用它做验收结论。</li>
 * </ul>
 *
 * <p>运行（先启动独立环境，见 {@code docs/tutorial/video-status-rebuild-paging.md}）：</p>
 * <pre>
 * docker compose -f compose.test.yaml up -d --wait
 * ./mvnw -DvideoStatusScale=true -Dtest=VideoStatusRebuildScaleIsolatedTests test
 * </pre>
 */
@EnabledIfSystemProperty(named = "videoStatusScale", matches = "true")
@SpringBootTest(classes = VideoStatusRebuildScaleIsolatedTests.TestApplication.class,
        properties = {
                "spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none",
                // 规模测量把读取预算显式放宽到 1 小时：预算语义另有单测覆盖，
                // 这里不能让默认 10s 预算把“测量”本身截断成失败。
                "app.video-status.rebuild-snapshot-budget-ms=3600000"
        })
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusRebuildScaleIsolatedTests.IsolationGuard.class)
class VideoStatusRebuildScaleIsolatedTests {

    /** 与现有隔离 IT 完全相同的端点保护：任何非 compose.test.yaml 覆盖都在连接前失败。 */
    static class IsolationGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var env = context.getEnvironment();
            Map<String, String> required = Map.of(
                    "spring.datasource.url", "jdbc:mysql://127.0.0.1:13307/feibijiubi?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                    "spring.datasource.username", "root",
                    "spring.datasource.password", "isolated-test-only",
                    "spring.data.redis.host", "127.0.0.1",
                    "spring.data.redis.port", "16379",
                    "spring.rabbitmq.host", "127.0.0.1",
                    "spring.rabbitmq.port", "15673",
                    "spring.rabbitmq.username", "isolated-test",
                    "spring.rabbitmq.password", "isolated-test-only");
            required.forEach((key, expected) -> {
                if (!expected.equals(env.getProperty(key))) {
                    throw new IllegalStateException("Refusing non-isolated test configuration: " + key);
                }
            });
            for (String key : List.of("spring.data.redis.url", "spring.data.redis.cluster.nodes",
                    "spring.data.redis.sentinel.master", "spring.rabbitmq.addresses",
                    "spring.datasource.jndi-name", "spring.datasource.hikari.jdbc-url")) {
                if (env.containsProperty(key)) throw new IllegalStateException("Unexpected connection override: " + key);
            }
        }
    }

    /**
     * 只装配重建需要的 Bean：没有任何 Rabbit 监听器、没有 flush/消费调度器，
     * 所以本类不会投递/消费消息，也不会改事件状态。
     */
    @Configuration(proxyBeanMethods = false)
    @Profile("video-status-isolated")
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
    @EnableConfigurationProperties(VideoStatusProperties.class)
    @MapperScan("com.feibijiubi.backend.mapper")
    @Import({RedisScriptConfig.class, VideoStatusRebuildServiceImpl.class,
            VideoStatusRebuildSnapshotServiceImpl.class, VideoStatusVidMutex.class,
            RedisUtils.class, JsonUtils.class})
    static class TestApplication {
        @Bean(destroyMethod = "shutdown")
        RedissonClient redissonClient() {
            Config config = new Config();
            config.useSingleServer().setAddress("redis://127.0.0.1:16379");
            return Redisson.create(config);
        }
    }

    private static final int REPETITIONS = 3;
    private static final long BASELINE_PLAY = 100L;
    private static final int INSERT_CHUNK = 500;
    private static final int REDIS_CHUNK = 1000;
    /** 与 {@code VideoStatusRebuildServiceImpl} 的固定批次一致：用于核对 pipeline 次数。 */
    private static final int PROCESSED_EVIDENCE_BATCH_SIZE = 256;
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 10, 23, 0, 0);

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    ObjectMapper json;
    @Autowired
    VideoStatusRebuildService rebuild;
    @Autowired
    VideoStatusProperties properties;
    /** 统计 pipeline 调用/命令数与门闩窗口；默认调用真实实现。 */
    @MockitoSpyBean
    RedisUtils redisUtils;

    private final AtomicInteger pipelineCalls = new AtomicInteger();
    private final AtomicLong existsCommands = new AtomicLong();
    private final AtomicLong gateOpenedNanos = new AtomicLong(-1L);
    private final AtomicLong gateReleasedNanos = new AtomicLong(-1L);

    int vid;
    String gateKey;
    List<String> seededEventIds = List.of();

    @BeforeEach
    void seedVideo() {
        // 双保险：即使 properties 被覆盖，也再确认一次真实连接指向隔离实例。
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                    .startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        String username = "scale-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) VALUES (?, 'test', 'test')", username);
        int uid = jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                + "VALUES (?, 'scale-test', 'test', 'test', '', '', '', '')", uid);
        vid = jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
        jdbc.update("INSERT INTO video_status(vid,play_times) VALUES (?,?)", vid, BASELINE_PLAY);
        gateKey = RedisKeyUtils.videoStatusRebuildGate(vid);
        resetCounters();
        installProbes();
    }

    @AfterEach
    void purgeOwnRedisState() {
        // 只删本测试 vid 自己的派生键与自己的 processed 证据；不删共享集合、不清测试库。
        redis.delete(List.of(RedisKeyUtils.videoStatus(vid), gateKey));
        redis.opsForSet().remove(RedisKeyUtils.dirtyVideo(), String.valueOf(vid));
        redis.opsForZSet().remove(RedisKeyUtils.feedHotVideos(), String.valueOf(vid));
        deleteProcessedKeys(seededEventIds);
        seededEventIds = List.of();
    }

    // ------------------------------------------------------------------ 规模用例

    @Test
    void zeroCandidatesColdRebuild() {
        measureScale(0);
    }

    @Test
    void oneThousandCandidatesColdRebuild() {
        measureScale(1_000);
    }

    @Test
    void oneHundredThousandCandidatesColdRebuild() {
        measureScale(100_000);
    }

    // ------------------------------------------------------------------ 测量主体

    private void measureScale(int candidates) {
        seededEventIds = seedCandidates(candidates);
        assertEquals(candidates, seededEventIds.size());
        assertEquals(candidates, dbCandidates());
        assertEquals(candidates, distinctEventIds());
        assertEquals((long) candidates, dbDeltaSum(), "预设 oracle：DB 增量总和必须等于候选数");

        List<Sample> samples = new ArrayList<>(REPETITIONS);
        for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
            samples.add(measureOnce(candidates, repetition));
        }

        // 每次都必须收敛到同一个预设 oracle：漏读/重复都会让这里失败。
        assertEquals(BASELINE_PLAY + candidates, currentPlay(), "预设 oracle：100 + 候选数");
        for (int repetition = 0; repetition < samples.size(); repetition++) {
            assertEquals(BASELINE_PLAY + candidates, samples.get(repetition).playTimes(),
                    "第 " + (repetition + 1) + " 次重建的 current 必须与预设 oracle 一致");
        }
        printSummary(candidates, samples);
    }

    private Sample measureOnce(int candidates, int repetition) {
        // 冷缓存：只删本测试 vid 的 current，门闩与 processed 证据保持真实状态。
        redis.delete(RedisKeyUtils.videoStatus(vid));
        resetCounters();

        List<MemoryPoolMXBean> heapPools = heapPools();
        heapPools.forEach(this::resetPeakQuietly);
        long usedBefore = usedHeapAfterGc();

        long startedAt = System.nanoTime();
        rebuild.ensureInitialized(vid);
        long rebuildNanos = System.nanoTime() - startedAt;

        long peakSum = heapPools.stream()
                .mapToLong(pool -> pool.getPeakUsage().getUsed())
                .sum();
        int pipelines = pipelineCalls.get();
        long commands = existsCommands.get();
        long gateOpen = gateOpenedNanos.get();
        long gateRelease = gateReleasedNanos.get();

        // 每次重复都断言，而不是只断言最后一次。
        long playTimes = currentPlay();
        assertEquals(BASELINE_PLAY + candidates, playTimes,
                "第 " + repetition + " 次重建漏读或重复计数");
        assertEquals(0L, currentCounter("likeTimes"));
        assertEquals(0L, currentCounter("unlikeTimes"));
        assertEquals(0L, currentCounter("commentTimes"));
        assertEquals(0L, currentCounter("coinTimes"));
        assertEquals(0L, currentCounter("shareTimes"));
        assertEquals(0L, currentCounter("collectTimes"));
        assertEquals(0L, currentCounter("danmuTimes"));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(gateKey)), "重建必须释放门闩");
        assertEquals(candidates, dbCandidates(), "重建不得改变事件状态");
        assertEquals(0, dbFlushed(), "重建不得把候选推进到 FLUSHED");
        assertEquals(candidates, distinctEventIds(), "重建不得新增/重复 eventId");

        // 有界批次的逻辑次数：每 256 条一次 pipeline，每条 status0 候选一条 EXISTS。
        assertEquals(expectedPipelines(candidates), pipelines,
                "pipeline 调用次数必须等于 ceil(候选数/256)");
        assertEquals((long) candidates, commands,
                "EXISTS 逻辑命令数必须等于 status0 候选数（不构造全量 key 集合）");
        assertTrue(gateOpen > 0 && gateRelease >= gateOpen,
                "门闩窗口必须可观测：open=" + gateOpen + ", release=" + gateRelease);

        Sample sample = new Sample(
                candidates,
                repetition,
                rebuildNanos / 1_000_000L,
                (gateRelease - gateOpen) / 1_000_000L,
                pipelines,
                commands,
                usedBefore,
                peakSum,
                playTimes
        );
        System.out.println(sample.line());
        return sample;
    }

    private void printSummary(int candidates, List<Sample> samples) {
        long bound = (long) properties.getRebuildMaxCandidates() + 1L;
        long min = samples.stream().mapToLong(Sample::rebuildMillis).min().orElseThrow();
        long max = samples.stream().mapToLong(Sample::rebuildMillis).max().orElseThrow();
        long median = samples.stream().mapToLong(Sample::rebuildMillis).sorted().boxed().toList()
                .get(samples.size() / 2);
        long gateMin = samples.stream().mapToLong(Sample::gateHoldMillis).min().orElseThrow();
        long gateMax = samples.stream().mapToLong(Sample::gateHoldMillis).max().orElseThrow();
        long peakOverBeforeMax = samples.stream()
                .mapToLong(s -> s.heapPoolPeakSumBytes() - s.heapUsedBeforeBytes())
                .max()
                .orElseThrow();
        System.out.println("T12-SCALE summary candidates=" + candidates
                + " reps=" + samples.size()
                + " rebuildMs[min/median/max]=" + min + "/" + median + "/" + max
                + " gateHoldMs[min/max]=" + gateMin + "/" + gateMax
                + " pipelinesPerRun=" + samples.get(0).pipelineCalls()
                + " existsCommandsPerRun=" + samples.get(0).existsCommands()
                + " candidateBound=" + bound
                + " configuredMaxCandidates=" + properties.getRebuildMaxCandidates()
                + " pageSize=" + properties.getRebuildCandidatePageSize()
                + " heapPeakOverBeforeMaxMiB=" + mib(peakOverBeforeMax)
                + " p95=n/a(samples=" + samples.size() + " < 20, diagnostic only)");
    }

    // ------------------------------------------------------------------ 造数

    /** 返回本测试 vid 的候选 eventId 列表；全部 status0（RECEIVED）+ 真实 processed 证据。 */
    private List<String> seedCandidates(int count) {
        List<String> eventIds = new ArrayList<>(count);
        int received = VideoStatusConsumeProcessStatus.RECEIVED.getCode();
        int ttlSeconds = Math.toIntExact(java.time.Duration.ofDays(
                properties.getRedisEventTtlDays()).toSeconds());
        for (int start = 0; start < count; start += INSERT_CHUNK) {
            int end = Math.min(start + INSERT_CHUNK, count);
            List<Object[]> args = new ArrayList<>(end - start);
            List<String> chunkIds = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                String eventId = "scale-" + vid + "-" + i;
                String payload = payload(eventId);
                chunkIds.add(eventId);
                args.add(new Object[]{
                        eventId,
                        vid,
                        VideoStatusEventType.PLAY.name(),
                        1L,
                        payload,
                        sha256Hex(payload),
                        received,
                        LocalDateTime.now(),
                        null
                });
            }
            // 一条多行 INSERT = 一次网络往返，避免 100000 次逐行插入。
            String values = String.join(",", Collections.nCopies(args.size(), "(?,?,?,?,?,?,?,?,?)"));
            jdbc.update("INSERT INTO video_status_consumed_event "
                    + "(event_id, vid, event_type, delta, payload, payload_hash, "
                    + "process_status, consumed_at, redis_applied_at) VALUES " + values,
                    args.stream().flatMap(Arrays::stream).toArray());
            writeProcessedKeys(chunkIds, ttlSeconds);
            eventIds.addAll(chunkIds);
        }
        return eventIds;
    }

    private String payload(String eventId) {
        VideoStatusChangedEvent event = new VideoStatusChangedEvent(
                eventId,
                vid,
                VideoStatusEventType.PLAY,
                1L,
                VideoStatusEventType.PLAY.calculateHotScore(1L),
                OCCURRED_AT,
                2,
                null
        );
        try {
            return json.writeValueAsString(event);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("无法生成候选 payload: " + eventId, e);
        }
    }

    /** 一次 pipeline 写入一批 processed 证据，避免逐条网络往返。 */
    private void writeProcessedKeys(List<String> eventIds, int ttlSeconds) {
        redis.executePipelined((RedisCallback<Object>) connection -> {
            RedisStringCommands commands = connection.stringCommands();
            for (String eventId : eventIds) {
                commands.set(
                        bytes(RedisKeyUtils.processedKey(eventId)),
                        bytes("1"),
                        Expiration.seconds(ttlSeconds),
                        RedisStringCommands.SetOption.upsert()
                );
            }
            return null;
        });
    }

    private void deleteProcessedKeys(List<String> eventIds) {
        for (int start = 0; start < eventIds.size(); start += REDIS_CHUNK) {
            int end = Math.min(start + REDIS_CHUNK, eventIds.size());
            List<String> keys = new ArrayList<>(end - start);
            for (String eventId : eventIds.subList(start, end)) {
                keys.add(RedisKeyUtils.processedKey(eventId));
            }
            redis.delete(keys);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 探针

    private void installProbes() {
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (gateKey.equals(call.getArgument(0))) {
                gateOpenedNanos.set(System.nanoTime());
            }
            return result;
        }).when(redisUtils).setString(anyString(), anyString());

        doAnswer(call -> {
            Object result = call.callRealMethod();
            List<String> keys = call.getArgument(1);
            if (keys != null && keys.size() == 1 && gateKey.equals(keys.get(0))) {
                gateReleasedNanos.set(System.nanoTime());
            }
            return result;
        }).when(redisUtils).executeScript(any(), anyList(), any(Object[].class));

        doAnswer(call -> {
            List<?> keys = call.getArgument(0);
            pipelineCalls.incrementAndGet();
            existsCommands.addAndGet(keys.size());
            return call.callRealMethod();
        }).when(redisUtils).hasKeys(anyList());
    }

    private void resetCounters() {
        pipelineCalls.set(0);
        existsCommands.set(0);
        gateOpenedNanos.set(-1L);
        gateReleasedNanos.set(-1L);
    }

    // ------------------------------------------------------------------ 观测辅助

    private static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .toList();
    }

    /** 池峰值只能作为“上界近似”：各池峰值不在同一时刻，且包含候选以外的临时对象。 */
    private void resetPeakQuietly(MemoryPoolMXBean pool) {
        try {
            pool.resetPeakUsage();
        } catch (UnsupportedOperationException ignored) {
            // 该池不支持峰值复位：保留 JVM 启动以来的峰值，报告时仍需按近似理解。
        }
    }

    private long usedHeapAfterGc() {
        System.gc();
        try {
            Thread.sleep(200L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static String mib(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1024.0 / 1024.0);
    }

    private static int expectedPipelines(int candidates) {
        return (candidates + PROCESSED_EVIDENCE_BATCH_SIZE - 1) / PROCESSED_EVIDENCE_BATCH_SIZE;
    }

    private long currentPlay() {
        return currentCounter("playTimes");
    }

    private long currentCounter(String field) {
        Object value = redis.opsForHash().get(RedisKeyUtils.videoStatus(vid), field);
        assertTrue(value != null, "current 缺少字段: " + field);
        return Long.parseLong((String) value);
    }

    private int dbCandidates() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event "
                + "WHERE vid=? AND process_status IN (0,1)", Integer.class, vid);
    }

    private int dbFlushed() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM video_status_consumed_event "
                + "WHERE vid=? AND process_status=2", Integer.class, vid);
    }

    private int distinctEventIds() {
        return jdbc.queryForObject("SELECT COUNT(DISTINCT event_id) FROM video_status_consumed_event "
                + "WHERE vid=?", Integer.class, vid);
    }

    private long dbDeltaSum() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(delta),0) FROM video_status_consumed_event "
                + "WHERE vid=?", Long.class, vid);
    }

    /** 单次测量样本；所有时间都是进程内时钟，内存字段都是可观测 heap 指标。 */
    private record Sample(
            int candidates,
            int repetition,
            long rebuildMillis,
            long gateHoldMillis,
            int pipelineCalls,
            long existsCommands,
            long heapUsedBeforeBytes,
            long heapPoolPeakSumBytes,
            long playTimes
    ) {
        String line() {
            return "T12-SCALE candidates=" + candidates
                    + " rep=" + repetition + "/" + REPETITIONS
                    + " rebuildMs=" + rebuildMillis
                    + " gateHoldMs=" + gateHoldMillis
                    + " pipelineCalls=" + pipelineCalls
                    + " existsCommands=" + existsCommands
                    + " heapUsedBeforeMiB=" + mib(heapUsedBeforeBytes)
                    + " heapPoolPeakSumMiB=" + mib(heapPoolPeakSumBytes)
                    + " heapPeakOverBeforeMiB=" + mib(heapPoolPeakSumBytes - heapUsedBeforeBytes)
                    + " playTimes=" + playTimes;
        }
    }
}
