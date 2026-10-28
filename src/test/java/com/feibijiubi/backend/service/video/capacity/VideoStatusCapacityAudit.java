package com.feibijiubi.backend.service.video.capacity;

import com.feibijiubi.backend.utils.redis.RedisConstants;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.async.RedisKeyAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.IntegerOutput;
import org.springframework.data.redis.connection.DefaultStringRedisConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * T14-A 只读容量核查 + 归档 dry-run 工具（测试作用域，没有生产调度器/HTTP 入口）。
 *
 * <p>它只做四件事，全部只读：</p>
 * <ol>
 *   <li>{@code information_schema.tables} 读取相关表的<b>估算</b>行数与数据/索引字节
 *       （不做 {@code COUNT(*)}，不扫表）；</li>
 *   <li>在缺索引时报告 {@code NOT_EVALUABLE} 并<b>跳过</b>查询，有索引时用
 *       {@code FORCE INDEX} + {@code EXPLAIN} 校验访问路径，再按时间窗 + LIMIT 列出
 *       <b>终态</b>候选的 id/时间/状态（不读 payload/last_error/eventId）；</li>
 *   <li>Redis {@code INFO memory} / {@code CONFIG GET maxmemory*} / {@code DBSIZE} 只读指标；</li>
 *   <li>有界 {@code SCAN}（<b>命令次数</b>预算，一次一页，不追游标）+ {@code MEMORY USAGE} 抽样
 *       processed key 单键字节（采样数量另有独立硬上限）。</li>
 * </ol>
 *
 * <p>禁止事项（本类刻意不包含）：{@code KEYS}、全量扫描、任何 {@code DELETE/UPDATE/INSERT/DDL}、
 * 任何 Redis 写命令、{@code CONFIG SET}、{@code FLUSHALL/FLUSHDB}、删除门闩或 processed key。</p>
 *
 * <p>status0/1/3 与 Outbox FAILED 永不列为可删除候选；即使终态（SENT=2 / FLUSHED=2）也一律标
 * {@code NOT_AUTHORIZED_FOR_DELETION}，因为保留期、最大重放窗口、DLQ 保留与审计期限都未决策，
 * 且没有“旧消息硬拒绝/去重墓碑”，processed 的 TTL 不能证明删除 DB 幂等证据安全。</p>
 */
final class VideoStatusCapacityAudit {

    static final String TABLE_OUTBOX = "video_status_outbox";
    static final String TABLE_CONSUMED = "video_status_consumed_event";
    static final String TABLE_REPAIR_LOG = "video_status_consumption_repair_log";
    static final String TABLE_STATUS = "video_status";

    static final List<String> AUDITED_TABLES =
            List.of(TABLE_OUTBOX, TABLE_CONSUMED, TABLE_REPAIR_LOG, TABLE_STATUS);

    /** outbox status=2 SENT：唯一终态。0=PENDING、1=SENDING、3=FAILED 都不是可删除候选。 */
    static final int OUTBOX_TERMINAL_STATUS = 2;
    /** consumed process_status=2 FLUSHED：唯一终态。0/1/3 都不是可删除候选。 */
    static final int CONSUMED_TERMINAL_STATUS = 2;

    /** T05 迁移提供的 {@code (status, created_at, id)} 索引：时间窗 + id 次序的有序范围扫描。 */
    static final String OUTBOX_WINDOW_INDEX = "idx_video_status_outbox_created";
    /** T05 迁移提供的 {@code (process_status, consumed_at, id)} 索引。 */
    static final String CONSUMED_WINDOW_INDEX = "idx_video_status_consumed_created";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final String TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%i:%s.%f";

    /** Redis SCAN 的结束游标：只有看到它才允许声称“遍历完成”。 */
    static final String FINISHED_CURSOR = "0";

    /** 单次 SCAN 回复的等待上限：卡住的连接不能把整个审计挂死，超时按读取失败处理。 */
    private static final long SCAN_REPLY_TIMEOUT_SECONDS = 5;

    /**
     * 运行参数。前三个是<b>配置假设</b>（不是测量）：假设日事件量、假设保留天数、dry-run 时间窗；
     * 其余是只读采样的边界。
     *
     * <p>有界性的口径：{@code scanMaxIterations} 是<b>SCAN 命令次数</b>（往返次数）硬上限；
     * {@code scanCount} 只是发给 Redis 的 {@code COUNT} hint（服务器可以返回更多或更少的 key，
     * 也可能一页返回 0 个）；{@code sampleMaxKeys} 是 {@code MEMORY USAGE} 采样次数的独立硬上限。
     * 三者不互相推导：不存在“轮数 × COUNT = 访问 key 预算”这种等式。</p>
     */
    record Options(int candidateLimit,
                   int windowDays,
                   long assumedDailyEvents,
                   int assumedRetentionDays,
                   int scanMaxIterations,
                   int scanCount,
                   int sampleMaxKeys,
                   String processedKeyPattern) {

        Options {
            if (candidateLimit < 1 || candidateLimit > 1000) {
                throw new IllegalArgumentException("candidateLimit 必须在 1..1000");
            }
            if (windowDays < 1 || windowDays > 3650) {
                throw new IllegalArgumentException("windowDays 必须在 1..3650");
            }
            if (assumedDailyEvents < 0) {
                throw new IllegalArgumentException("assumedDailyEvents 不能为负（0 表示未提供假设）");
            }
            if (assumedRetentionDays < 0 || assumedRetentionDays > 3650) {
                throw new IllegalArgumentException("assumedRetentionDays 必须在 0..3650（0 = 保留期未决策）");
            }
            if (scanMaxIterations < 1 || scanMaxIterations > 1000) {
                throw new IllegalArgumentException("scanMaxIterations 必须在 1..1000（SCAN 命令次数上限）");
            }
            if (scanCount < 1 || scanCount > 1000) {
                throw new IllegalArgumentException("scanCount 必须在 1..1000（COUNT hint，不是硬返回上限）");
            }
            if (sampleMaxKeys < 1 || sampleMaxKeys > 1000) {
                throw new IllegalArgumentException("sampleMaxKeys 必须在 1..1000");
            }
            if (processedKeyPattern == null || processedKeyPattern.isBlank()
                    || "*".equals(processedKeyPattern.trim())) {
                throw new IllegalArgumentException("processedKeyPattern 必须是受限模式，不允许裸 *");
            }
        }

        /**
         * 默认值与 {@code -DvideoStatusCapacity*} 覆盖。
         *
         * <p>假设类参数默认 0（未提供 → 估算 UNKNOWN），避免把“没给假设”变成“0 B/天”。</p>
         */
        static Options fromSystemProperties() {
            return new Options(
                    intProperty("videoStatusCapacityLimit", 20),
                    intProperty("videoStatusCapacityWindowDays", 30),
                    longProperty("videoStatusCapacityAssumedDailyEvents", 0L),
                    intProperty("videoStatusCapacityAssumedRetentionDays", 0),
                    intProperty("videoStatusCapacityScanIterations", 20),
                    intProperty("videoStatusCapacityScanCount", 200),
                    intProperty("videoStatusCapacitySampleKeys", 100),
                    System.getProperty("videoStatusCapacityProcessedKeyPattern",
                            RedisConstants.PROCESSED_PREFIX + "*"));
        }

        private static int intProperty(String name, int defaultValue) {
            return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
        }

        private static long longProperty(String name, long defaultValue) {
            return Long.parseLong(System.getProperty(name, Long.toString(defaultValue)));
        }
    }

    /**
     * 一个终态窗口定义：表、状态列、时间列、终态状态值与必需的索引。
     *
     * <p>所有标识符都来自代码常量，仍做白名单校验，避免未来被当成外部输入拼进 SQL。</p>
     */
    record Window(String table, String statusColumn, String timeColumn, int terminalStatus, String requiredIndex) {

        Window {
            for (String identifier : List.of(table, statusColumn, timeColumn, requiredIndex)) {
                if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
                    throw new IllegalArgumentException("非法 SQL 标识符: " + identifier);
                }
            }
            if (terminalStatus != OUTBOX_TERMINAL_STATUS) {
                throw new IllegalArgumentException("T14-A 只允许终态 status=2（SENT/FLUSHED）作为候选");
            }
        }
    }

    static final Window OUTBOX_WINDOW = new Window(
            TABLE_OUTBOX, "status", "created_at", OUTBOX_TERMINAL_STATUS, OUTBOX_WINDOW_INDEX);
    static final Window CONSUMED_WINDOW = new Window(
            TABLE_CONSUMED, "process_status", "consumed_at", CONSUMED_TERMINAL_STATUS, CONSUMED_WINDOW_INDEX);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final Options options;

    VideoStatusCapacityAudit(JdbcTemplate jdbc, StringRedisTemplate redis, Options options) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.options = options;
    }

    VideoStatusCapacityReport run() {
        String generatedAt = jdbc.queryForObject(
                "SELECT DATE_FORMAT(NOW(3), '" + TIMESTAMP_FORMAT + "')", String.class);
        List<VideoStatusCapacityReport.TableSize> tables = readTableSizes();
        List<VideoStatusCapacityReport.CandidateWindow> windows =
                List.of(evaluateWindow(OUTBOX_WINDOW), evaluateWindow(CONSUMED_WINDOW));
        VideoStatusCapacityReport.RedisMemory memory = readRedisMemory();
        VideoStatusCapacityReport.ProcessedKeySample sample = sampleProcessedKeys();

        VideoStatusCapacityEstimator.Inputs inputs = new VideoStatusCapacityEstimator.Inputs(
                options.assumedDailyEvents(),
                bytesPerRow(tables, TABLE_OUTBOX),
                bytesPerRow(tables, TABLE_CONSUMED),
                sample.minBytes(),
                sample.maxBytes(),
                sample.sampledKeys(),
                memory.usedMemoryBytes(),
                memory.maxMemoryBytes(),
                options.assumedRetentionDays());

        List<VideoStatusCapacityReport.Estimate> estimates = List.of(
                new VideoStatusCapacityReport.Estimate("DB 日增（outbox+consumed）",
                        VideoStatusCapacityEstimator.dbDailyBytes(inputs), "B/天"),
                new VideoStatusCapacityReport.Estimate("Redis processed 日增",
                        VideoStatusCapacityEstimator.processedDailyBytes(inputs), "B/天"),
                new VideoStatusCapacityReport.Estimate("processed key 数量",
                        VideoStatusCapacityEstimator.processedKeyCount(inputs), "个"),
                new VideoStatusCapacityReport.Estimate("Redis processed 保留占用",
                        VideoStatusCapacityEstimator.processedRetainedBytes(inputs), "B"),
                new VideoStatusCapacityReport.Estimate("Redis 预计耗尽天数",
                        VideoStatusCapacityEstimator.redisExhaustionDays(inputs), "天"));

        return new VideoStatusCapacityReport(generatedAt, tables, windows, memory, sample, inputs,
                estimates, collectUncertainties(tables, windows, memory, sample, estimates),
                options.windowDays(), options.candidateLimit());
    }

    /**
     * 评估一个终态窗口。
     *
     * <p>顺序：索引前缀校验 → {@code EXPLAIN} 访问路径校验 → 有界查询。
     * 任一校验失败都返回 {@code NOT_EVALUABLE} 且候选为空，<b>不</b>降级为全表扫描。</p>
     */
    VideoStatusCapacityReport.CandidateWindow evaluateWindow(Window window) {
        List<String> expectedColumns = List.of(window.statusColumn(), window.timeColumn(), "id");
        List<String> actualColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? "
                        + "ORDER BY seq_in_index",
                String.class, window.table(), window.requiredIndex());
        if (!expectedColumns.equals(actualColumns)) {
            return notEvaluable(window, "INDEX_MISSING：需要 " + expectedColumns
                    + "，实际 " + actualColumns + "；缺索引不降级为全表扫描");
        }

        String sql = "SELECT id, DATE_FORMAT(" + window.timeColumn() + ", '" + TIMESTAMP_FORMAT
                + "') AS ts, " + window.statusColumn()
                + " FROM " + window.table() + " FORCE INDEX (" + window.requiredIndex() + ")"
                + " WHERE " + window.statusColumn() + " = ?"
                + " AND " + window.timeColumn() + " < DATE_SUB(NOW(3), INTERVAL ? DAY)"
                + " ORDER BY " + window.timeColumn() + ", id"
                + " LIMIT ?";
        int probeLimit = options.candidateLimit() + 1;

        List<Map<String, Object>> plan = jdbc.queryForList(
                "EXPLAIN " + sql, window.terminalStatus(), options.windowDays(), probeLimit);
        String accessType = plan.isEmpty() ? null : String.valueOf(column(plan.get(0), "type"));
        String usedKey = plan.isEmpty() ? null : String.valueOf(column(plan.get(0), "key"));
        String plannedRows = plan.isEmpty() ? null : String.valueOf(column(plan.get(0), "rows"));
        if (plan.isEmpty() || !window.requiredIndex().equals(usedKey)
                || "ALL".equalsIgnoreCase(accessType)) {
            return notEvaluable(window, "ACCESS_PATH_NOT_INDEXED：EXPLAIN type=" + accessType
                    + " key=" + usedKey + "；不做无索引扫描");
        }
        String accessPath = "key=" + usedKey + " type=" + accessType
                + " rows≈" + plannedRows + "（EXPLAIN 估算）";

        List<Map<String, Object>> rows = jdbc.queryForList(
                sql, window.terminalStatus(), options.windowDays(), probeLimit);
        boolean truncated = rows.size() > options.candidateLimit();
        List<VideoStatusCapacityReport.TerminalCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(rows.size(), options.candidateLimit()))) {
            Object idValue = column(row, "id");
            Object statusValue = column(row, window.statusColumn());
            if (!(idValue instanceof Number id) || !(statusValue instanceof Number status)) {
                return notEvaluable(window, "UNEXPECTED_ROW_SHAPE：id/status 不是数值，候选不可评估");
            }
            if (status.intValue() != window.terminalStatus()) {
                // 防御：SQL 只按终态等值过滤，出现其它状态说明协议被改坏，宁可不可评估也不列出来。
                return notEvaluable(window, "UNEXPECTED_STATUS：" + status.intValue()
                        + " 不是终态，候选不可评估");
            }
            candidates.add(new VideoStatusCapacityReport.TerminalCandidate(
                    window.table(), id.longValue(), String.valueOf(column(row, "ts")),
                    status.intValue(), VideoStatusCapacityReport.DELETION_NOT_AUTHORIZED));
        }
        return new VideoStatusCapacityReport.CandidateWindow(window.table(),
                VideoStatusCapacityReport.WINDOW_EVALUABLE, window.requiredIndex(), accessPath,
                candidates, truncated, null);
    }

    private static VideoStatusCapacityReport.CandidateWindow notEvaluable(Window window, String reason) {
        return new VideoStatusCapacityReport.CandidateWindow(window.table(),
                VideoStatusCapacityReport.WINDOW_NOT_EVALUABLE, window.requiredIndex(), null,
                List.of(), false, reason);
    }

    // ------------------------------------------------------------------ MySQL 体积

    private List<VideoStatusCapacityReport.TableSize> readTableSizes() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT table_name, table_rows, data_length, index_length "
                        + "FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name IN (?, ?, ?, ?)",
                AUDITED_TABLES.toArray());
        Map<String, Map<String, Object>> byTable = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byTable.put(String.valueOf(column(row, "table_name")).toLowerCase(Locale.ROOT), row);
        }
        List<VideoStatusCapacityReport.TableSize> sizes = new ArrayList<>(AUDITED_TABLES.size());
        for (String table : AUDITED_TABLES) {
            Map<String, Object> row = byTable.get(table);
            if (row == null) {
                sizes.add(new VideoStatusCapacityReport.TableSize(table, null, null, null, null));
                continue;
            }
            Long estimatedRows = longOrNull(column(row, "table_rows"));
            Long dataBytes = longOrNull(column(row, "data_length"));
            Long indexBytes = longOrNull(column(row, "index_length"));
            Long total = dataBytes == null || indexBytes == null ? null : dataBytes + indexBytes;
            sizes.add(new VideoStatusCapacityReport.TableSize(
                    table, estimatedRows, dataBytes, indexBytes, total));
        }
        return sizes;
    }

    private static Long bytesPerRow(List<VideoStatusCapacityReport.TableSize> tables, String table) {
        for (VideoStatusCapacityReport.TableSize size : tables) {
            if (!size.table().equals(table)) {
                continue;
            }
            if (size.estimatedRows() == null || size.estimatedRows() <= 0 || size.totalBytes() == null) {
                return null;
            }
            return size.totalBytes() / size.estimatedRows();
        }
        return null;
    }

    // ---------------------------------------------------------------- Redis 只读

    private VideoStatusCapacityReport.RedisMemory readRedisMemory() {
        Properties info = null;
        Properties config = null;
        Long dbSize = null;
        List<String> notes = new ArrayList<>();
        try {
            info = redis.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().info("memory"));
        } catch (RuntimeException failure) {
            notes.add("INFO memory 读取失败（" + failure.getClass().getSimpleName()
                    + "）：used_memory 记为 UNKNOWN");
        }
        try {
            config = redis.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().getConfig("maxmemory*"));
        } catch (RuntimeException failure) {
            notes.add("CONFIG GET maxmemory* 读取失败（" + failure.getClass().getSimpleName()
                    + "）：maxmemory 记为 UNKNOWN，不猜测配置");
        }
        try {
            dbSize = redis.execute((RedisCallback<Long>) connection ->
                    connection.serverCommands().dbSize());
        } catch (RuntimeException failure) {
            notes.add("DBSIZE 读取失败（" + failure.getClass().getSimpleName() + "）");
        }
        return new VideoStatusCapacityReport.RedisMemory(
                info == null ? null : parseLong(info.getProperty("used_memory")),
                info == null ? null : parseLong(info.getProperty("used_memory_peak")),
                config == null ? null : parseLong(config.getProperty("maxmemory")),
                config == null ? null : config.getProperty("maxmemory-policy"),
                dbSize,
                notes.isEmpty() ? null : String.join("；", notes));
    }

    /**
     * 有界 SCAN + MEMORY USAGE 抽样 processed key 单键字节。
     *
     * <p><b>“bounded”在这里的确切含义</b>：{@link ScanPageReader#scan(String)} 一次调用只发一条
     * {@code SCAN}（单页），命令次数硬上限是 {@code scanMaxIterations}，达到后立即停；
     * 不使用 Spring Data 的 {@code Cursor#hasNext()}，因为它在匹配为空时会<b>自己</b>继续请求，
     * 直到遍历完整个 keyspace —— 那等于“命令次数无界”，{@code scanMaxIterations} 形同虚设。
     * {@code COUNT} 只是 hint，单页可能返回多于或少于 {@code COUNT} 的 key，也可能 0 个；
     * {@code MEMORY USAGE} 的采样次数有<b>独立</b>硬上限 {@code sampleMaxKeys}。
     * 游标未归零 = 覆盖截断（{@code scanComplete=false}）；空匹配页永远不被当作“遍历完成”。</p>
     */
    private VideoStatusCapacityReport.ProcessedKeySample sampleProcessedKeys() {
        ScanArgs scanArgs = ScanArgs.Builder.matches(options.processedKeyPattern())
                .limit(options.scanCount());
        try {
            // exposeConnection=true：默认的 RedisTemplate 会包一层代理，代理不是 LettuceConnection，
            // 就拿不到 MEMORY USAGE 的显式 IntegerOutput 与 SCAN 的单页游标 API。
            VideoStatusCapacityReport.ProcessedKeySample sample = redis.execute(
                    (RedisCallback<VideoStatusCapacityReport.ProcessedKeySample>) connection ->
                            sampleWith(cursor -> scanOnce(connection, scanArgs, cursor),
                                    key -> memoryUsage(connection, key), options),
                    true);
            if (sample == null) {
                return toSample(new ScanRun(List.of(), 0, 0, false, 0, "SCAN 未返回结果（回调未执行）"), options);
            }
            return sample;
        } catch (RuntimeException failure) {
            return toSample(new ScanRun(List.of(), 0, 0, false, 0,
                    "SCAN/MEMORY USAGE 读取失败（" + failure.getClass().getSimpleName() + "）"), options);
        }
    }

    /** 一页 SCAN 的结果：{@code cursor} 为 {@link #FINISHED_CURSOR} 才表示遍历结束。 */
    record ScanPage(List<byte[]> keys, String cursor) {
    }

    /** 单页 SCAN 读取器：一次调用 = 一次 SCAN 往返，绝不能内部继续追游标。 */
    @FunctionalInterface
    interface ScanPageReader {
        ScanPage scan(String cursor);
    }

    /** {@code MEMORY USAGE} 读取器：返回 null 表示这一次没读到数字（不猜 0）。 */
    @FunctionalInterface
    interface KeySizeReader {
        Long sizeOf(byte[] key);
    }

    /**
     * 一次抽样的原始事实：已发出的 SCAN 命令次数、SCAN 返回的 key 数、成功采样的字节数，
     * 以及（可选）失败原因。命令次数与实际往返一一对应，是这里唯一的有界性证据。
     */
    record ScanRun(List<Long> sizes, int returnedKeys, int scanCommands,
                   boolean scanComplete, int memoryUsageMisses, String failure) {
    }

    /** 把“读取器 + 限额”跑成报告样本；任何读失败都收敛为 UNKNOWN，不让整个审计报错。 */
    static VideoStatusCapacityReport.ProcessedKeySample sampleWith(ScanPageReader reader,
                                                                   KeySizeReader sizes,
                                                                   Options options) {
        try {
            return toSample(collectScanRun(reader, options, sizes), options);
        } catch (RuntimeException failure) {
            return toSample(new ScanRun(List.of(), 0, 0, false, 0,
                    "SCAN/MEMORY USAGE 读取失败（" + failure.getClass().getSimpleName() + "）"), options);
        }
    }

    /**
     * 显式单页游标循环：每轮只发一条 SCAN 并计数，游标归零才结束；命令预算用完立即返回。
     *
     * <p>不用 {@code while (cursor.hasNext())}，也不为“空匹配页”重试或提前结束。</p>
     */
    static ScanRun collectScanRun(ScanPageReader reader, Options options, KeySizeReader sizes) {
        int commandLimit = options.scanMaxIterations();
        int sampleLimit = options.sampleMaxKeys();
        List<Long> sampled = new ArrayList<>();
        int returnedKeys = 0;
        int commands = 0;
        int misses = 0;
        String cursor = null;
        while (commands < commandLimit) {
            commands++;
            ScanPage page;
            try {
                page = reader.scan(cursor);
            } catch (RuntimeException failure) {
                return new ScanRun(sampled, returnedKeys, commands, false, misses,
                        "SCAN 读取失败（" + failure.getClass().getSimpleName() + "）");
            }
            if (page == null || page.cursor() == null || page.cursor().isBlank()) {
                // 拿不到游标就无法判断是否遍历完成，宁可失败也不谎称“完成”。
                return new ScanRun(sampled, returnedKeys, commands, false, misses,
                        "SCAN 返回空游标，无法判断是否遍历完成");
            }
            List<byte[]> keys = page.keys() == null ? List.of() : page.keys();
            returnedKeys += keys.size();
            for (byte[] key : keys) {
                if (sampled.size() >= sampleLimit) {
                    break;
                }
                if (key == null) {
                    misses++;
                    continue;
                }
                Long size;
                try {
                    size = sizes.sizeOf(key);
                } catch (RuntimeException failure) {
                    misses++;
                    continue;
                }
                if (size == null || size < 0) {
                    misses++;
                    continue;
                }
                sampled.add(size);
            }
            cursor = page.cursor();
            if (FINISHED_CURSOR.equals(cursor)) {
                return new ScanRun(sampled, returnedKeys, commands, true, misses, null);
            }
            if (sampled.size() >= sampleLimit) {
                return new ScanRun(sampled, returnedKeys, commands, false, misses, null);
            }
        }
        return new ScanRun(sampled, returnedKeys, commands, false, misses, null);
    }

    /** 把原始事实变成报告样本：样本不足/读失败 → UNKNOWN，命令数与游标状态永远写进 note。 */
    static VideoStatusCapacityReport.ProcessedKeySample toSample(ScanRun run, Options options) {
        VideoStatusCapacityReport.ScanBudget budget = new VideoStatusCapacityReport.ScanBudget(
                run.scanCommands(), options.scanMaxIterations(), options.sampleMaxKeys(), options.scanCount());
        String facts = "SCAN 命令 " + run.scanCommands() + "/" + options.scanMaxIterations()
                + " 次（COUNT=" + options.scanCount() + " 只是 hint），返回 key " + run.returnedKeys()
                + " 个，游标归零=" + (run.scanComplete() ? "是" : "否（覆盖截断）")
                + (run.memoryUsageMisses() > 0 ? "，MEMORY USAGE 无结果 " + run.memoryUsageMisses() + " 个" : "");
        if (run.failure() != null) {
            return new VideoStatusCapacityReport.ProcessedKeySample(run.sizes().size(), run.returnedKeys(),
                    budget, false, null, null, null,
                    VideoStatusCapacityReport.SAMPLE_STATUS_UNKNOWN, run.failure() + "：" + facts);
        }
        if (run.sizes().size() < VideoStatusCapacityEstimator.MIN_PROCESSED_SAMPLE_KEYS) {
            return new VideoStatusCapacityReport.ProcessedKeySample(run.sizes().size(), run.returnedKeys(),
                    budget, run.scanComplete(), null, null, null,
                    VideoStatusCapacityReport.SAMPLE_STATUS_UNKNOWN,
                    "样本不足：命中 " + run.sizes().size() + " 个 processed key，少于 "
                            + VideoStatusCapacityEstimator.MIN_PROCESSED_SAMPLE_KEYS + " 个（" + facts + "）");
        }
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long sum = 0;
        for (Long size : run.sizes()) {
            min = Math.min(min, size);
            max = Math.max(max, size);
            sum += size;
        }
        long average = sum / run.sizes().size();
        return new VideoStatusCapacityReport.ProcessedKeySample(run.sizes().size(), run.returnedKeys(),
                budget, run.scanComplete(), min, max, average,
                VideoStatusCapacityReport.SAMPLE_STATUS_SAMPLED,
                run.scanComplete() ? null : facts + "；样本只覆盖已扫描部分，未统计 processed key 总数");
    }

    /**
     * 一条 {@code SCAN <cursor> MATCH <pattern> COUNT <n>}，返回单页游标与本次 key。
     *
     * <p>显式使用 Lettuce 的单页游标 API（{@code RedisKeyAsyncCommands#scan(ScanCursor, ScanArgs)}
     * 的 future 只对应<b>一条</b>命令）：一次调用一次往返，不像 {@code RedisTemplate#scan()} 的
     * {@code Cursor} 那样在内部循环到游标归零。</p>
     */
    private static ScanPage scanOnce(RedisConnection connection, ScanArgs scanArgs, String cursor) {
        if (!(unwrap(connection) instanceof LettuceConnection lettuce)) {
            throw new IllegalStateException("SCAN 单页游标需要 Lettuce 连接");
        }
        RedisKeyAsyncCommands<byte[], byte[]> commands = lettuce.getNativeConnection();
        RedisFuture<KeyScanCursor<byte[]>> reply = cursor == null
                ? commands.scan(scanArgs)
                : commands.scan(ScanCursor.of(cursor), scanArgs);
        try {
            KeyScanCursor<byte[]> page = reply.get(SCAN_REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new ScanPage(page.getKeys(), page.getCursor());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SCAN 等待被中断", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("SCAN 命令失败", failure);
        }
    }

    /**
     * {@code MEMORY USAGE <key>}（只读，O(1) 于 key 长度）。
     *
     * <p>Spring Data 的 {@code RedisConnection#execute(String, byte[]...)} 对未知命令默认用
     * {@code ByteArrayOutput}，而 MEMORY USAGE 的整数回复在该 output 上会抛
     * {@code UnsupportedOperationException}，所以显式给出 {@link IntegerOutput}；
     * 拿不到数字时返回 null（→ 样本 UNKNOWN），不猜、不补 0。</p>
     */
    private static Long memoryUsage(RedisConnection connection, byte[] key) {
        if (!(unwrap(connection) instanceof LettuceConnection lettuce)) {
            return null;
        }
        Object reply = lettuce.execute("MEMORY", new IntegerOutput<>(ByteArrayCodec.INSTANCE),
                "USAGE".getBytes(StandardCharsets.UTF_8), key);
        return reply instanceof Number number ? number.longValue() : null;
    }

    /** {@code StringRedisTemplate} 的回调拿到的是 {@code DefaultStringRedisConnection} 包装层，先剥掉。 */
    private static RedisConnection unwrap(RedisConnection connection) {
        RedisConnection candidate = connection;
        while (candidate instanceof DefaultStringRedisConnection wrapper) {
            candidate = wrapper.getDelegate();
        }
        return candidate;
    }

    // ---------------------------------------------------------------- 未确定项

    private static List<String> collectUncertainties(
            List<VideoStatusCapacityReport.TableSize> tables,
            List<VideoStatusCapacityReport.CandidateWindow> windows,
            VideoStatusCapacityReport.RedisMemory memory,
            VideoStatusCapacityReport.ProcessedKeySample sample,
            List<VideoStatusCapacityReport.Estimate> estimates) {
        List<String> uncertainties = new ArrayList<>();
        for (VideoStatusCapacityReport.TableSize table : tables) {
            if (table.estimatedRows() == null || table.totalBytes() == null) {
                uncertainties.add(table.table() + " 体积/行数 UNKNOWN：information_schema 无可用估算，"
                        + "不做 COUNT(*) 全表统计");
            }
        }
        for (VideoStatusCapacityReport.CandidateWindow window : windows) {
            if (VideoStatusCapacityReport.WINDOW_NOT_EVALUABLE.equals(window.status())) {
                uncertainties.add(window.table() + " 终态窗口 NOT_EVALUABLE：" + window.reason());
            }
        }
        if (!VideoStatusCapacityReport.SAMPLE_STATUS_SAMPLED.equals(sample.status())) {
            uncertainties.add("processed key 单键字节 UNKNOWN：" + sample.note());
        }
        if (!sample.scanComplete()) {
            uncertainties.add("processed key 抽样覆盖截断：SCAN 执行 "
                    + sample.budget().scanCommands() + " 次后游标仍未归零（命令预算 "
                    + sample.budget().scanCommandLimit() + " 次），单键字节只覆盖已扫描部分；"
                    + "不补充扫描、不推断 processed key 总数");
        }
        if (memory.maxMemoryBytes() == null) {
            uncertainties.add("maxmemory UNKNOWN：CONFIG GET 未读到（耗尽天数因此 UNKNOWN）");
        }
        for (VideoStatusCapacityReport.Estimate estimate : estimates) {
            if (estimate.range().isUnknown()) {
                uncertainties.add(estimate.label() + " UNKNOWN：" + estimate.range().note());
            }
        }
        return uncertainties;
    }

    private static Object column(Map<String, Object> row, String name) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Long longOrNull(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
