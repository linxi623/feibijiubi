package com.feibijiubi.backend.service.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.entity.VideoStatusReconciliationEventRow;
import com.feibijiubi.backend.entity.VideoStatusReconciliationRelationAggregate;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusReconciliationMapper;
import com.feibijiubi.backend.service.impl.video.videostatus.VideoStatusReconciliationServiceImpl;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusReconciliationService;
import com.feibijiubi.backend.utils.JsonUtils;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.operation.RedisHashOperations;
import com.feibijiubi.backend.vo.VideoStatusReconciliationVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T10 只读对账的真实隔离集成测试：MySQL 13307 + Redis 16379，绝不复用部署环境。
 *
 * <p>与单元测试不同，本类使用真实 Mapper XML 和真实 SQL，验收：</p>
 * <ul>
 *   <li>{@code video_status} 八项计数（含 {@link Long#MAX_VALUE}）的真实字段映射；</li>
 *   <li>账本按 process_status 计数、事件投影窗口、user_video 关系聚合的
 *       {@code limit + 1} 有界截断（截断只能当“至少/下界”）；</li>
 *   <li>事件投影只读取 id/process_status/event_type/delta，不选择 payload、last_error；</li>
 *   <li>Redis current Hash 八项计数的正常、缺失、部分缺失、非 canonical、负数与溢出，
 *       未知值绝不当 0；</li>
 *   <li>T10 {@code (vid, id)} 索引迁移可重复执行，且真实事件窗口查询的可选索引包含它。</li>
 * </ul>
 *
 * <p>只新增随机 user/video/video_status/consumed/user_video 行并写入本测试自己的
 * Redis vid key；不删除任何 MySQL 行，也不清空任何现有表/现有业务数据。</p>
 *
 * <p>运行：{@code ./mvnw.cmd -o -DvideoStatusIT=true -Dtest=VideoStatusReconciliationIsolatedTests test}</p>
 */
@EnabledIfSystemProperty(named = "videoStatusIT", matches = "true")
@SpringBootTest(classes = VideoStatusReconciliationIsolatedTests.TestApplication.class,
        properties = {"spring.config.location=classpath:video-status-isolated.properties",
                "spring.main.web-application-type=none"})
@ActiveProfiles("video-status-isolated")
@ContextConfiguration(initializers = VideoStatusCounterIsolatedTests.IsolationGuard.class)
class VideoStatusReconciliationIsolatedTests {

    private static final Path MIGRATION = Path.of(
            "database/migrations/t10-video-status-reconciliation-indexes.sql");

    private static final List<String> REDIS_FIELDS = List.of(
            "playTimes", "likeTimes", "unlikeTimes", "commentTimes",
            "coinTimes", "shareTimes", "collectTimes", "danmuTimes");

    /** 只启动 DataSource/MyBatis/Redis 与对账服务，不启动 RabbitMQ/Redisson/调度器。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
            "org.redisson.spring.starter.RedissonAutoConfigurationV2"})
    @MapperScan(basePackageClasses = VideoStatusReconciliationMapper.class)
    @Import({VideoStatusReconciliationServiceImpl.class,
            RedisHashOperations.class, JsonUtils.class})
    static class TestApplication {
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    VideoStatusReconciliationMapper reconciliationMapper;
    @Autowired
    VideoStatusReconciliationService reconciliationService;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    ObjectMapper objectMapper;

    int vid;
    String redisKey;

    @BeforeEach
    void seed() throws Exception {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(connection.getMetaData().getURL()
                            .startsWith("jdbc:mysql://127.0.0.1:13307/feibijiubi"),
                    "the isolated MySQL endpoint must be the only target");
        }

        vid = newVideo();
        jdbc.update("INSERT INTO video_status(vid) VALUES (?)", vid);
        redisKey = RedisKeyUtils.videoStatus(vid);

        // 每个新 vid 的 Redis key 由本测试拥有；先清理上次中断可能留下的同名 key。
        redis.delete(redisKey);
        redis.opsForHash().putAll(redisKey, canonicalZeroCounters());
    }

    @AfterEach
    void cleanupOwnRedisState() {
        // 只删除本测试自己新建的 vid key，绝不触碰其他业务的 Redis 数据。
        redis.delete(redisKey);
    }

    // ---------------------------------------------------------------- 1) DB 字段映射

    @Test
    void realMapperReadsAllEightDbCountersIncludingLongMax() {
        long play = 3_000_000_000L;
        jdbc.update("UPDATE video_status SET play_times=?, like_times=?, unlike_times=?, "
                        + "comment_times=?, coin_times=?, share_times=?, collect_times=?, "
                        + "danmu_times=? WHERE vid=?",
                play, Long.MAX_VALUE, 1L, 2L, 3L, 4L, 5L, 6L, vid);

        VideoStatus mapped = reconciliationMapper.selectDbCounts(vid);
        assertNotNull(mapped);
        assertEquals(vid, mapped.getVid());
        assertEquals(play, mapped.getPlayTimes());
        assertEquals(Long.MAX_VALUE, mapped.getLikeTimes(), "BIGINT UNSIGNED -> Long 映射必须保持 Long.MAX");
        assertEquals(1L, mapped.getUnlikeTimes());
        assertEquals(2L, mapped.getCommentTimes());
        assertEquals(3L, mapped.getCoinTimes());
        assertEquals(4L, mapped.getShareTimes());
        assertEquals(5L, mapped.getCollectTimes());
        assertEquals(6L, mapped.getDanmuTimes());

        VideoStatusReconciliationVO result = reconciliationService.reconcile(vid, 100);

        assertEquals(VideoStatusReconciliationVO.MODE, result.getMode());
        assertTrue(result.isReadOnly());
        assertFalse(result.isWritePerformed());
        assertFalse(result.isAutomaticRepair());
        assertEquals(VideoStatusReconciliationVO.VERDICT_NO_SAFE_CONCLUSION, result.getVerdict());
        assertTrue(result.getUncertainties()
                .contains(VideoStatusReconciliationVO.CONCURRENT_OBSERVATION_NOT_ATOMIC));
        assertTrue(result.getDb().isRowPresent());
        assertEquals(play, result.getDb().getPlayTimes());
        assertEquals(Long.MAX_VALUE, result.getDb().getLikeTimes());
        assertEquals(6L, result.getDb().getDanmuTimes());
        assertEquals("SAMPLED", result.getRedis().getStatus());
        assertFalse(result.isIncomplete());
    }

    @Test
    void missingDbStatusRowStaysUnknownAndIsNeverTreatedAsZero() {
        int videoWithoutStatus = newVideo();

        assertNull(reconciliationMapper.selectDbCounts(videoWithoutStatus));

        VideoStatusReconciliationVO result =
                reconciliationService.reconcile(videoWithoutStatus, 100);
        assertFalse(result.getDb().isRowPresent());
        assertNull(result.getDb().getPlayTimes());
        assertNull(result.getDb().getLikeTimes());
        assertNull(result.getDb().getDanmuTimes());
        assertTrue(result.getUncertainties().contains("DB_STATUS_ROW_MISSING"));
        assertTrue(result.isIncomplete());
    }

    // -------------------------------------------------------------- 2) 有界账本计数

    @Test
    void ledgerCountIsBoundedByCapAndReportsTruncation() {
        insertConsumed("PLAY", 1L, 0);
        insertConsumed("LIKE", 1L, 0);
        insertConsumed("PLAY", 1L, 1);
        insertConsumed("LIKE", 1L, 1);
        insertConsumed("COIN", 1L, 1);
        insertConsumed("SHARE", 1L, 1);
        insertConsumed("DANMU", 1L, 2);

        int limit = 2;
        int cap = limit + 1;
        assertEquals(2L, reconciliationMapper.countLedgerByStatus(vid, 0, cap));
        assertEquals(3L, reconciliationMapper.countLedgerByStatus(vid, 1, cap),
                "4 rows with cap=3 must return the cap, i.e. a lower bound");
        assertEquals(1L, reconciliationMapper.countLedgerByStatus(vid, 2, cap));
        assertEquals(0L, reconciliationMapper.countLedgerByStatus(vid, 3, cap));

        VideoStatusReconciliationVO result = reconciliationService.reconcile(vid, limit);

        assertEquals(cap, result.getLedger().getCap());
        assertEquals(2L, result.getLedger().getStatus0());
        assertEquals(3L, result.getLedger().getStatus1());
        assertEquals(1L, result.getLedger().getStatus2());
        assertEquals(0L, result.getLedger().getStatus3());
        assertTrue(result.getLedger().isTruncated());
        assertTrue(result.getUncertainties().contains("LEDGER_COUNTS_TRUNCATED"));
        assertTrue(result.getLedger().getNote().contains("下界"));
        assertTrue(result.isIncomplete(), "truncated ledger must never be a complete conclusion");
    }

    // ----------------------------------------------------------- 3) 事件安全投影

    @Test
    void eventProjectionUsesSafeFieldsAndBigIntegerDeltaSums() throws Exception {
        String payloadSecret = "payload-secret-" + UUID.randomUUID();
        String lastErrorSecret = "last-error-secret-" + UUID.randomUUID();
        insertConsumed("PLAY", 3L, 2, payloadSecret, lastErrorSecret);
        insertConsumed("LIKE", Long.MAX_VALUE, 2);
        insertConsumed("LIKE", Long.MAX_VALUE, 2);
        insertConsumed("MYSTERY_EVENT", 7L, 1);

        List<Long> idsDesc = jdbc.queryForList(
                "SELECT id FROM video_status_consumed_event WHERE vid=? ORDER BY id DESC",
                Long.class, vid);
        assertEquals(4, idsDesc.size());

        List<VideoStatusReconciliationEventRow> rows =
                reconciliationMapper.selectEventProjection(vid, 100);
        assertEquals(4, rows.size());
        assertEquals(idsDesc, rows.stream()
                .map(VideoStatusReconciliationEventRow::getId)
                .collect(Collectors.toList()), "mapper must use ORDER BY id DESC");
        assertEquals("MYSTERY_EVENT", rows.get(0).getEventType());
        assertEquals(7L, rows.get(0).getDelta());
        assertEquals(Integer.valueOf(1), rows.get(0).getProcessStatus());

        Set<String> declaredFields = Arrays.stream(
                        VideoStatusReconciliationEventRow.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("id", "processStatus", "eventType", "delta"), declaredFields,
                "the projection entity must not expose payload/last_error/lease token");

        VideoStatusReconciliationVO result = reconciliationService.reconcile(vid, 100);
        VideoStatusReconciliationVO.EventProjection projection = result.getEventProjection();
        assertFalse(projection.isTruncated());
        assertEquals(4, projection.getRowCount());
        assertEquals(BigInteger.valueOf(3L), projection.getDeltaByType().get("PLAY"));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO),
                projection.getDeltaByType().get("LIKE"),
                "event delta sum must use BigInteger beyond Long range");
        assertEquals(1, projection.getUnknownTypeCount());
        assertEquals(BigInteger.valueOf(7L), projection.getUnknownTypeDelta());
        assertEquals(idsDesc.get(idsDesc.size() - 1), projection.getMinEventId());
        assertEquals(idsDesc.get(0), projection.getMaxEventId());
        assertEquals(BigInteger.ZERO, projection.getDeltaByType().get(
                VideoStatusEventType.DANMU.name()));

        String serialized = objectMapper.writeValueAsString(result);
        assertFalse(serialized.contains(payloadSecret), "response must not contain payload details");
        assertFalse(serialized.contains(lastErrorSecret), "response must not contain raw last_error");
        assertFalse(result.isIncomplete());
    }

    @Test
    void eventProjectionWindowIsBoundedToLimitPlusOne() {
        insertConsumed("PLAY", 1L, 2);
        insertConsumed("LIKE", 2L, 2);
        insertConsumed("COIN", 3L, 2);
        insertConsumed("SHARE", 4L, 2);
        List<Long> idsDesc = jdbc.queryForList(
                "SELECT id FROM video_status_consumed_event WHERE vid=? ORDER BY id DESC",
                Long.class, vid);

        int limit = 2;
        int cap = limit + 1;
        List<VideoStatusReconciliationEventRow> rows =
                reconciliationMapper.selectEventProjection(vid, cap);
        assertEquals(cap, rows.size(), "mapper reads at most limit + 1 rows");

        VideoStatusReconciliationVO result = reconciliationService.reconcile(vid, limit);
        VideoStatusReconciliationVO.EventProjection projection = result.getEventProjection();
        assertTrue(projection.isTruncated());
        assertEquals(cap, projection.getRowCount());
        assertEquals(idsDesc.get(0), projection.getMaxEventId());
        assertEquals(idsDesc.get(2), projection.getMinEventId(),
                "only the newest cap rows are visible; the oldest event is outside the window");
        assertEquals(BigInteger.ZERO, projection.getDeltaByType().get("PLAY"),
                "the oldest PLAY event is outside the truncated window");
        assertTrue(result.getUncertainties().contains("EVENT_PROJECTION_TRUNCATED"));
        assertTrue(result.isIncomplete());
    }

    // ---------------------------------------------------------- 4) 关系有界真值

    @Test
    void relationAggregateIsExactBelowCapAndUsable() {
        int likedUser = newUser();
        int unlikedUser = newUser();
        insertUserVideo(likedUser, 1, 0, 2, 1);
        insertUserVideo(unlikedUser, 0, 1, 1, 0);

        VideoStatusReconciliationRelationAggregate aggregate =
                reconciliationMapper.selectRelationAggregate(vid, 101);
        assertNotNull(aggregate);
        assertEquals(2L, aggregate.getSampledRows());
        assertEquals(1L, aggregate.getLikedRows());
        assertEquals(1L, aggregate.getUnlikedRows());
        assertEquals(3L, aggregate.getCoinSum());
        assertEquals(1L, aggregate.getCollectRows());

        VideoStatusReconciliationVO result = reconciliationService.reconcile(vid, 100);
        VideoStatusReconciliationVO.RelationTruth relations = result.getRelations();
        assertTrue(relations.isSampled());
        assertFalse(relations.isTruncated());
        assertFalse(relations.isLowerBound());
        assertTrue(relations.isUsableForConclusion());
        assertEquals(2L, relations.getSampledRows());
        assertEquals(1L, relations.getLikedRows());
        assertEquals(1L, relations.getUnlikedRows());
        assertEquals(BigInteger.valueOf(3L), relations.getCoinSum());
        assertEquals(1L, relations.getCollectRows());
        assertFalse(result.getUncertainties().contains("RELATION_SAMPLE_TRUNCATED"));
        assertFalse(result.isIncomplete());
    }

    @Test
    void relationAggregateAtCapBecomesExplicitLowerBound() {
        // 4 行完全相同的业务关系：LIMIT 3 之后聚合结果与顺序无关，可稳定验收截断语义。
        for (int i = 0; i < 4; i++) {
            insertUserVideo(newUser(), 1, 0, 2, 1);
        }

        int limit = 2;
        int cap = limit + 1;
        VideoStatusReconciliationRelationAggregate aggregate =
                reconciliationMapper.selectRelationAggregate(vid, cap);
        assertNotNull(aggregate);
        assertEquals((long) cap, aggregate.getSampledRows());
        assertEquals(3L, aggregate.getLikedRows());
        assertEquals(6L, aggregate.getCoinSum());
        assertEquals(3L, aggregate.getCollectRows());

        VideoStatusReconciliationVO result = reconciliationService.reconcile(vid, limit);
        VideoStatusReconciliationVO.RelationTruth relations = result.getRelations();
        assertTrue(relations.isSampled());
        assertTrue(relations.isTruncated());
        assertTrue(relations.isLowerBound());
        assertFalse(relations.isUsableForConclusion());
        assertEquals((long) cap, relations.getSampledRows());
        assertTrue(result.getUncertainties().contains("RELATION_SAMPLE_TRUNCATED"));
        assertTrue(result.isIncomplete());
    }

    // ------------------------------------------------------- 5) T10 索引迁移

    @Test
    void t10WindowIndexMigrationIsIdempotentAndUsable() throws Exception {
        assertTrue(Files.isRegularFile(MIGRATION),
                "missing migration: " + MIGRATION.toAbsolutePath());
        String script = Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .toUpperCase(Locale.ROOT);
        for (String forbidden : List.of("DROP TABLE", "TRUNCATE", "DELETE FROM",
                "UPDATE VIDEO_STATUS_CONSUMED_EVENT", "ALTER TABLE VIDEO_STATUS_CONSUMED_EVENT")) {
            assertFalse(script.contains(forbidden),
                    "T10 migration must stay index-only and non-destructive: " + forbidden);
        }

        // 在同一隔离库执行两次，验证幂等：第二次必须是 SELECT 1 no-op。
        for (int run = 0; run < 2; run++) {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(MIGRATION));
                return null;
            });
        }

        List<Map<String, Object>> indexColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name = 'video_status_consumed_event' "
                        + "AND index_name = 'idx_video_status_consumed_vid_id' "
                        + "ORDER BY seq_in_index");
        assertEquals(List.of("vid", "id"), indexColumns.stream()
                        .map(row -> String.valueOf(column(row, "column_name")).toLowerCase(Locale.ROOT))
                        .collect(Collectors.toList()),
                "the bounded event window needs a real (vid, id) index");

        List<Map<String, Object>> plan = jdbc.queryForList(
                "EXPLAIN SELECT id, process_status, event_type, delta "
                        + "FROM video_status_consumed_event WHERE vid = ? "
                        + "ORDER BY id DESC LIMIT ?", vid, 101);
        assertFalse(plan.isEmpty());
        String possibleKeys = String.valueOf(column(plan.get(0), "possible_keys"));
        assertTrue(possibleKeys.contains("idx_video_status_consumed_vid_id"),
                "real event projection SQL must be able to use the (vid, id) index, got: "
                        + possibleKeys);
    }

    // ------------------------------------------------------------- 6) Redis 计数

    @Test
    void redisEightCountersAreSampledExactly() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("playTimes", Long.toString(Long.MAX_VALUE));
        values.put("likeTimes", "9007199254740993");
        values.put("unlikeTimes", "0");
        values.put("commentTimes", "2");
        values.put("coinTimes", "3");
        values.put("shareTimes", "4");
        values.put("collectTimes", "5");
        values.put("danmuTimes", "6");
        redis.opsForHash().putAll(redisKey, values);

        VideoStatusReconciliationVO result = reconciliationService.reconcile(vid, 100);
        VideoStatusReconciliationVO.RedisSample sampled = result.getRedis();

        assertEquals("SAMPLED", sampled.getStatus());
        assertEquals(Long.MAX_VALUE, sampled.getPlayTimes());
        assertEquals(9_007_199_254_740_993L, sampled.getLikeTimes());
        assertEquals(0L, sampled.getUnlikeTimes());
        assertEquals(2L, sampled.getCommentTimes());
        assertEquals(3L, sampled.getCoinTimes());
        assertEquals(4L, sampled.getShareTimes());
        assertEquals(5L, sampled.getCollectTimes());
        assertEquals(6L, sampled.getDanmuTimes());
        assertTrue(sampled.getInvalidFields().isEmpty());
        assertFalse(result.getUncertainties().contains("REDIS_SAMPLE_INVALID"));
        assertFalse(result.getUncertainties().contains("REDIS_SAMPLE_INCOMPLETE"));
        assertFalse(result.getUncertainties().contains("REDIS_NOT_SAMPLED"));
        assertTrue(sampled.getNote().contains("只读采样"));
        assertFalse(result.isIncomplete());
    }

    @Test
    void missingRedisHashAndPartialFieldsAreUnknownNotZero() {
        redis.delete(redisKey);
        VideoStatusReconciliationVO missing =
                reconciliationService.reconcile(vid, 100);
        assertEquals("NOT_SAMPLED_OR_MISSING", missing.getRedis().getStatus());
        assertAllCounterValuesNull(missing.getRedis());
        assertTrue(missing.getUncertainties().contains("REDIS_NOT_SAMPLED"));
        assertTrue(missing.isIncomplete());

        // 只有 playTimes：其余七个字段必须报告为缺失，八项计数全部保持 null。
        redis.opsForHash().put(redisKey, "playTimes", "5");
        VideoStatusReconciliationVO partial =
                reconciliationService.reconcile(vid, 100);
        assertEquals("INCOMPLETE_FIELDS", partial.getRedis().getStatus());
        assertAllCounterValuesNull(partial.getRedis());
        assertEquals(List.of("likeTimes", "unlikeTimes", "commentTimes", "coinTimes",
                        "shareTimes", "collectTimes", "danmuTimes"),
                partial.getRedis().getInvalidFields());
        assertTrue(partial.getUncertainties().contains("REDIS_SAMPLE_INCOMPLETE"));
        assertTrue(partial.isIncomplete());
    }

    @Test
    void nonCanonicalRedisCountersAreInvalidAndNeverNormalized() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("playTimes", "01");
        values.put("likeTimes", "+2");
        values.put("unlikeTimes", " 3");
        values.put("commentTimes", "1.5");
        values.put("coinTimes", "0");
        values.put("shareTimes", "0");
        values.put("collectTimes", "0");
        values.put("danmuTimes", "0");
        redis.opsForHash().putAll(redisKey, values);

        VideoStatusReconciliationVO result = reconciliationService.reconcile(vid, 100);
        VideoStatusReconciliationVO.RedisSample sampled = result.getRedis();

        assertEquals("INVALID_COUNTER", sampled.getStatus());
        assertEquals(List.of("playTimes", "likeTimes", "unlikeTimes", "commentTimes"),
                sampled.getInvalidFields());
        assertAllCounterValuesNull(sampled);
        assertTrue(result.getUncertainties().contains("REDIS_SAMPLE_INVALID"));
        assertTrue(result.isIncomplete());
    }

    @Test
    void overflowAndNegativeRedisCountersAreInvalidAndNeverZero() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("playTimes", "9223372036854775808"); // Long.MAX_VALUE + 1
        values.put("likeTimes", "-1");
        values.put("unlikeTimes", "0");
        values.put("commentTimes", "0");
        values.put("coinTimes", "0");
        values.put("shareTimes", "0");
        values.put("collectTimes", "0");
        values.put("danmuTimes", "0");
        redis.opsForHash().putAll(redisKey, values);

        VideoStatusReconciliationVO result = reconciliationService.reconcile(vid, 100);
        VideoStatusReconciliationVO.RedisSample sampled = result.getRedis();

        assertEquals("INVALID_COUNTER", sampled.getStatus());
        assertEquals(List.of("playTimes", "likeTimes"), sampled.getInvalidFields());
        assertAllCounterValuesNull(sampled);
        assertTrue(result.getUncertainties().contains("REDIS_SAMPLE_INVALID"));
        assertTrue(sampled.getNote().contains("溢出"));
        assertTrue(result.isIncomplete());
    }

    // ---------------------------------------------------------------- helpers

    private int newVideo() {
        int uid = newUser();
        jdbc.update("INSERT INTO video(uid,title,mc_id,sc_id,cover_url,cover_key,video_url,video_key) "
                        + "VALUES (?, 't10-reconciliation', 'test', 'test', '', '', '', '')",
                uid);
        return jdbc.queryForObject("SELECT MAX(vid) FROM video WHERE uid=?", Integer.class, uid);
    }

    private int newUser() {
        String username = "t10rec-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users(username,password_hash,nickname) "
                + "VALUES (?, 'test', 'test')", username);
        return jdbc.queryForObject("SELECT id FROM users WHERE username=?", Integer.class, username);
    }

    private Map<String, String> canonicalZeroCounters() {
        Map<String, String> zeros = new LinkedHashMap<>();
        REDIS_FIELDS.forEach(field -> zeros.put(field, "0"));
        return zeros;
    }

    private void insertConsumed(String eventType, long delta, int processStatus) {
        insertConsumed(eventType, delta, processStatus, null, null);
    }

    private void insertConsumed(String eventType, long delta, int processStatus,
                                String payloadSecret, String lastErrorSecret) {
        String payload = payloadSecret == null
                ? "{\"test\":true}"
                : "{\"secret\":\"" + payloadSecret + "\"}";
        jdbc.update("INSERT INTO video_status_consumed_event("
                        + "event_id, vid, event_type, delta, payload, payload_hash, "
                        + "process_status, last_error) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "t10rec-" + UUID.randomUUID(), vid, eventType, delta, payload,
                "0".repeat(64), processStatus, lastErrorSecret);
    }

    private void insertUserVideo(int uid, int liked, int unliked, int coin, int collect) {
        jdbc.update("INSERT INTO user_video(vid, uid, liked, unliked, coin, collect) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                vid, uid, liked, unliked, coin, collect);
    }

    private static void assertAllCounterValuesNull(VideoStatusReconciliationVO.RedisSample sample) {
        assertNull(sample.getPlayTimes());
        assertNull(sample.getLikeTimes());
        assertNull(sample.getUnlikeTimes());
        assertNull(sample.getCommentTimes());
        assertNull(sample.getCoinTimes());
        assertNull(sample.getShareTimes());
        assertNull(sample.getCollectTimes());
        assertNull(sample.getDanmuTimes());
    }

    private static Object column(Map<String, Object> row, String name) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
