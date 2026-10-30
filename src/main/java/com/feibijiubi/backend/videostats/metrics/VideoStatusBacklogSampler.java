package com.feibijiubi.backend.videostats.metrics;

import com.feibijiubi.backend.videostats.config.VideoStatusMetricsConfig;
import com.feibijiubi.backend.videostats.config.VideoStatusProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * T05 积压采样器：定时、<b>只读</b>地读取共享数据库的 backlog，并给出告警。
 *
 * <p>关键语义（详见 {@code docs/tutorial/video-status-observability.md}）：</p>
 * <ul>
 *   <li>每 15 秒采样一次，跑在 {@link VideoStatusMetricsConfig} 提供的
 *       独立单线程调度器上，不占用发布/落库/恢复线程；</li>
 *   <li>只有采样线程会查数据库：HTTP 抓取只读缓存的快照，
 *       非采样时刻没有额外查询；</li>
 *   <li>计数封顶 {@link #COUNT_CAP}，返回 {@code count >= 10001} 的语义，
 *       不对无限增长大表做无界 {@code COUNT(*)}；</li>
 *   <li>采样失败保留上次成功结果并标记 {@code STALE}；从未成功过则是
 *       {@code UNKNOWN}，计数字段为 null 而不是假 0；</li>
 *   <li>快照保存最近一次<b>尝试</b>（成功或失败），读取时按当前时钟重新
 *       判定，超过 {@link #SNAPSHOT_STALE_AFTER_MILLIS} 没有新采样就不再是 OK；</li>
 *   <li>覆盖 outbox 全部状态 0/1/2/3（含 SENT），consumed 覆盖 0/1/3；</li>
 *   <li>角色已开启但从未心跳：启动宽限 30s 内是 UNKNOWN，之后按 STALLED 告警；</li>
 *   <li>旧库未执行 T05 migration（缺观测索引）时，只输出诊断 +
 *       {@code DEGRADED}，不退化成全表扫描；</li>
 *   <li>不写 Redis、不改事件状态、不为门闩加 TTL：观测不能影响幂等与恢复语义。</li>
 * </ul>
 *
 * <p>多实例：backlog 来自共享 MySQL，是全局值；operation 计数
 * （{@link VideoStatusTelemetry}）是本实例值。不要把两者相加或互相校验。</p>
 */
@Component
public class VideoStatusBacklogSampler {

    private static final Logger log = LoggerFactory.getLogger(VideoStatusBacklogSampler.class);

    /** 采样间隔：15 秒；与“最老 >120s / 心跳 >30s”的测试阈值配套。 */
    public static final long SAMPLE_INTERVAL_MILLIS = 15_000L;
    /** 首次采样的延迟，避免和启动期连接池/迁移抢资源。 */
    public static final long INITIAL_DELAY_MILLIS = 5_000L;
    /** 计数上限：返回 10001 表示“至少 10001 条”，不再追求精确值。 */
    public static final int COUNT_CAP = 10_001;
    /** 待处理（outbox 0/1、consumed 0/1）最老年龄告警阈值：120 秒。 */
    public static final long PENDING_ALERT_AGE_MILLIS = 120_000L;
    /** 关键调度心跳告警阈值：30 秒。 */
    public static final long SCHEDULER_ALERT_AGE_MILLIS = 30_000L;
    /**
     * 快照过期阈值：45 秒（连续 3 个采样周期都没有新结果）。
     * 超过后 {@code /metrics} 不能再显示 OK，避免“采样线程已停但接口永远健康”。
     */
    public static final long SNAPSHOT_STALE_AFTER_MILLIS = 45_000L;

    public static final String STATE_OK = "OK";
    public static final String STATE_STALE = "STALE";
    public static final String STATE_UNKNOWN = "UNKNOWN";
    public static final String STATE_DEGRADED = "DEGRADED";
    public static final String STATE_STALLED = "STALLED";

    public static final String SOURCE_OUTBOX = "OUTBOX";
    public static final String SOURCE_CONSUMED = "CONSUMED";

    public static final String OUTBOX_CREATED_INDEX = "idx_video_status_outbox_created";
    public static final String CONSUMED_CREATED_INDEX = "idx_video_status_consumed_created";

    private static final String OUTBOX_TABLE = "video_status_outbox";
    private static final String CONSUMED_TABLE = "video_status_consumed_event";
    private static final String MIGRATION_HINT =
            "database/migrations/t05-video-status-observability-indexes.sql";

    private static final int[] TRACKED_OUTBOX_STATUSES = {0, 1, 2, 3};
    private static final int[] TRACKED_CONSUMED_STATUSES = {0, 1, 3};
    private static final Map<Integer, String> OUTBOX_STATUS_LABELS = Map.of(
            0, "PENDING",
            1, "SENDING",
            2, "SENT",
            3, "FAILED"
    );
    private static final Map<Integer, String> CONSUMED_STATUS_LABELS = Map.of(
            0, "RECEIVED",
            1, "REDIS_APPLIED_PENDING_FLUSH",
            2, "FLUSHED",
            3, "REPAIR_REQUIRED"
    );

    private final VideoStatusMetricsMapper metricsMapper;
    private final VideoStatusTelemetry telemetry;
    private final VideoStatusProperties properties;
    private final ScheduledExecutorService samplerExecutor;
    private final Clock clock;
    private final Object sampleLock = new Object();

    private volatile Snapshot lastSuccess;
    /** 最近一次尝试（成功、失败或 DEGRADED）；HTTP 快照以它为准，不能只看 lastSuccess。 */
    private volatile Snapshot lastAttempt;
    private final long startedAtEpochMillis;
    private volatile ScheduledFuture<?> samplingFuture;

    /** 生产构造：两个构造器必须显式标注，否则 Spring 找不到默认构造会启动失败。 */
    @Autowired
    public VideoStatusBacklogSampler(VideoStatusMetricsMapper metricsMapper,
                                     VideoStatusTelemetry telemetry,
                                     VideoStatusProperties properties,
                                     @Qualifier(VideoStatusMetricsConfig.METRICS_SCHEDULER_BEAN)
                                     ScheduledExecutorService samplerExecutor) {
        this(metricsMapper, telemetry, properties, samplerExecutor, Clock.systemUTC());
    }

    /** 测试可注入固定时钟与假调度器，不启动任何基础设施。 */
    public VideoStatusBacklogSampler(VideoStatusMetricsMapper metricsMapper,
                                     VideoStatusTelemetry telemetry,
                                     VideoStatusProperties properties,
                                     ScheduledExecutorService samplerExecutor,
                                     Clock clock) {
        this.metricsMapper = metricsMapper;
        this.telemetry = telemetry;
        this.properties = properties;
        this.samplerExecutor = samplerExecutor;
        this.clock = clock;
        // 启动宽限期起点：角色已开启但从未心跳时，超过阈值也要能判定 STALLED。
        this.startedAtEpochMillis = clock.millis();
    }

    @PostConstruct
    void startSampling() {
        if (samplerExecutor == null || samplerExecutor.isShutdown()) {
            log.warn("视频统计积压采样未启动：metrics 调度器不可用");
            return;
        }
        samplingFuture = samplerExecutor.scheduleWithFixedDelay(
                this::sampleSafely,
                INITIAL_DELAY_MILLIS,
                SAMPLE_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    void stopSampling() {
        ScheduledFuture<?> future = samplingFuture;
        if (future != null) {
            future.cancel(false);
            samplingFuture = null;
        }
    }

    /** 调度线程入口：任何异常都不允许杀死采样任务。 */
    void sampleSafely() {
        try {
            Snapshot snapshot = sampleOnce();
            if (snapshot.isStale()) {
                log.warn("视频统计积压采样状态={}，最近错误={}",
                        snapshot.getState(), snapshot.getLastError());
            }
            for (Alarm alarm : snapshot.getAlarms()) {
                log.warn("视频统计告警[{}] {}: {}",
                        alarm.getSeverity(), alarm.getCode(), alarm.getMessage());
            }
        } catch (RuntimeException e) {
            // sampleOnce 已兜住 SQL 异常；这里兜住构造快照等意外错误。
            log.warn("视频统计积压采样任务异常，等待下个周期", e);
        }
    }

    /**
     * 执行一次同步采样（测试直接调用）。
     *
     * <p>成功：覆盖 lastSuccess；失败：返回保留 lastSuccess 数值的
     * STALE/UNKNOWN/DEGRADED 快照，不把失败写成 0。</p>
     */
    public Snapshot sampleOnce() {
        synchronized (sampleLock) {
            long now = clock.millis();
            Snapshot attempt;
            try {
                attempt = doSample(now);
            } catch (RuntimeException e) {
                attempt = failedSnapshot(now, sanitize(e));
            }
            Snapshot evaluated = withSchedulerEvaluated(now, attempt);
            if (STATE_OK.equals(evaluated.getState())) {
                lastSuccess = evaluated;
            }
            // 成功/失败/DEGRADED 都是“尝试过”：只缓存 lastSuccess 会让失败被永久掩盖。
            lastAttempt = evaluated;
            return evaluated;
        }
    }

    /**
     * 最近一次采样尝试（成功或失败），读取时按当前时钟重新判定是否过期，
     * 并基于本实例遥测重算调度心跳告警。此方法不查数据库。
     */
    public Snapshot snapshot() {
        long now = clock.millis();
        Snapshot attempted = lastAttempt;
        if (attempted == null) {
            return withSchedulerEvaluated(now, unknownSnapshot(now));
        }
        Snapshot evaluated = withSchedulerEvaluated(now, attempted);
        long age = Math.max(0L, now - evaluated.getSampledAtEpochMillis());
        if (age <= SNAPSHOT_STALE_AFTER_MILLIS) {
            return evaluated;
        }
        String state = evaluated.getState();
        String lastError = evaluated.getLastError();
        if (STATE_OK.equals(state)) {
            state = STATE_STALE;
        }
        if (lastError == null) {
            lastError = "最近一次采样已超过 " + SNAPSHOT_STALE_AFTER_MILLIS
                    + "ms 未更新，采样线程可能已停摆";
        }
        // 采样停摆时缓存不能永久 OK：标记 stale 并保留上次已知数值。
        return new Snapshot(
                state,
                true,
                evaluated.getSampledAtEpochMillis(),
                evaluated.getLastSuccessAtEpochMillis(),
                lastError,
                evaluated.getDiagnostics(),
                evaluated.getBacklog(),
                evaluated.getSchedulerHeartbeats(),
                evaluated.getAlarms()
        );
    }

    private Snapshot doSample(long now) {
        List<String> diagnostics = new ArrayList<>();
        boolean outboxIndexed = hasIndex(OUTBOX_TABLE, OUTBOX_CREATED_INDEX);
        boolean consumedIndexed = hasIndex(CONSUMED_TABLE, CONSUMED_CREATED_INDEX);

        if (!outboxIndexed) {
            diagnostics.add("缺少索引 " + OUTBOX_CREATED_INDEX
                    + " (status, created_at, id)：请先执行 " + MIGRATION_HINT
                    + "；迁移前不执行计数，避免对无限增长大表全表扫描");
        }
        if (!consumedIndexed) {
            diagnostics.add("缺少索引 " + CONSUMED_CREATED_INDEX
                    + " (process_status, consumed_at, id)：请先执行 " + MIGRATION_HINT
                    + "；迁移前不执行计数，避免对无限增长大表全表扫描");
        }
        if (!outboxIndexed || !consumedIndexed) {
            return degradedSnapshot(now, diagnostics);
        }

        List<BacklogReading> readings = new ArrayList<>(7);
        for (int status : TRACKED_OUTBOX_STATUSES) {
            Integer count = metricsMapper.countOutboxStatusCapped(status, COUNT_CAP);
            readings.add(buildReading(
                    SOURCE_OUTBOX, status, OUTBOX_STATUS_LABELS.get(status), count,
                    count != null && count > 0
                            ? metricsMapper.selectOldestOutboxAgeMillis(status)
                            : null
            ));
        }
        for (int status : TRACKED_CONSUMED_STATUSES) {
            Integer count = metricsMapper.countConsumedStatusCapped(status, COUNT_CAP);
            readings.add(buildReading(
                    SOURCE_CONSUMED, status, CONSUMED_STATUS_LABELS.get(status), count,
                    count != null && count > 0
                            ? metricsMapper.selectOldestConsumedAgeMillis(status)
                            : null
            ));
        }

        return new Snapshot(
                STATE_OK,
                false,
                now,
                now,
                null,
                List.of(),
                readings,
                List.of(),
                List.of()
        );
    }

    /** 用当前时钟重算调度心跳读数与告警；只读本实例遥测，不查数据库。 */
    private Snapshot withSchedulerEvaluated(long now, Snapshot base) {
        List<SchedulerReading> schedulerReadings = schedulerReadings(now);
        List<Alarm> alarms = buildAlarms(base.getBacklog(), schedulerReadings);
        return new Snapshot(
                base.getState(),
                base.isStale(),
                base.getSampledAtEpochMillis(),
                base.getLastSuccessAtEpochMillis(),
                base.getLastError(),
                base.getDiagnostics(),
                base.getBacklog(),
                schedulerReadings,
                alarms
        );
    }

    private boolean hasIndex(String tableName, String indexName) {
        return metricsMapper.indexExists(tableName, indexName) > 0;
    }

    private BacklogReading buildReading(String source,
                                        int status,
                                        String statusLabel,
                                        Integer count,
                                        Long oldestAgeMillis) {
        Boolean capped = count == null ? null : count >= COUNT_CAP;
        // 数据库 NOW(3) 与应用时钟可能有微小偏差：负年龄按 0 处理，不伪造“未来”。
        Long age = oldestAgeMillis == null
                ? null
                : Long.valueOf(Math.max(0L, oldestAgeMillis));
        return new BacklogReading(
                source,
                status,
                statusLabel,
                count == null ? null : Long.valueOf(count),
                capped,
                age
        );
    }

    private List<SchedulerReading> schedulerReadings(long now) {
        VideoStatusTelemetry.Snapshot telemetrySnapshot = telemetry.snapshot();
        Map<String, VideoStatusTelemetry.OperationSnapshot> byOperation = new LinkedHashMap<>();
        for (VideoStatusTelemetry.OperationSnapshot operation : telemetrySnapshot.getOperations()) {
            byOperation.put(operation.getOperation(), operation);
        }
        List<SchedulerReading> readings = new ArrayList<>(3);
        readings.add(schedulerReading(
                VideoStatusTelemetry.Operation.RELAY_TICK,
                isRelayRoleEnabled(),
                byOperation,
                now
        ));
        readings.add(schedulerReading(
                VideoStatusTelemetry.Operation.FLUSH_TICK,
                isSchedulingRoleEnabled(),
                byOperation,
                now
        ));
        readings.add(schedulerReading(
                VideoStatusTelemetry.Operation.RECOVERY_TICK,
                isSchedulingRoleEnabled(),
                byOperation,
                now
        ));
        return readings;
    }

    private SchedulerReading schedulerReading(VideoStatusTelemetry.Operation operation,
                                              boolean roleEnabledOnThisInstance,
                                              Map<String, VideoStatusTelemetry.OperationSnapshot> byOperation,
                                              long now) {
        VideoStatusTelemetry.OperationSnapshot operationSnapshot = byOperation.get(operation.name());
        Long lastHeartbeat = operationSnapshot == null
                ? null
                : operationSnapshot.getLastHeartbeatAtEpochMillis();
        Long age = lastHeartbeat == null
                ? null
                : Long.valueOf(Math.max(0L, now - lastHeartbeat));
        String state;
        if (lastHeartbeat == null) {
            long sinceStarted = Math.max(0L, now - startedAtEpochMillis);
            if (roleEnabledOnThisInstance && sinceStarted > SCHEDULER_ALERT_AGE_MILLIS) {
                // 角色已开启却始终没有心跳：过了启动宽限期仍无心跳，说明调度没跑起来。
                state = STATE_STALLED;
                age = Long.valueOf(sinceStarted);
            } else {
                // 启动宽限期内不产生假告警，也不假装健康。
                state = STATE_UNKNOWN;
            }
        } else if (age != null && age > SCHEDULER_ALERT_AGE_MILLIS) {
            state = STATE_STALLED;
        } else {
            state = STATE_OK;
        }
        return new SchedulerReading(
                operation.name(),
                roleEnabledOnThisInstance,
                lastHeartbeat,
                age,
                state
        );
    }

    private List<Alarm> buildAlarms(List<BacklogReading> readings,
                                    List<SchedulerReading> schedulerReadings) {
        List<Alarm> alarms = new ArrayList<>();
        for (BacklogReading reading : readings) {
            if (reading.getCount() == null) {
                continue;
            }
            long count = reading.getCount();
            boolean pendingStatus = reading.getStatus() == 0 || reading.getStatus() == 1;
            if (Boolean.TRUE.equals(reading.getCountCapped())) {
                alarms.add(new Alarm(
                        "BACKLOG_CAPPED",
                        "WARN",
                        reading.getSource() + "/" + reading.getStatusLabel(),
                        "积压已达采样上限 " + COUNT_CAP + " 条（实际 >= " + COUNT_CAP
                                + "），需要人工排查积压原因"
                ));
            }
            if (pendingStatus && count > 0
                    && reading.getOldestAgeMillis() != null
                    && reading.getOldestAgeMillis() > PENDING_ALERT_AGE_MILLIS) {
                alarms.add(new Alarm(
                        "PENDING_TOO_OLD",
                        "WARN",
                        reading.getSource() + "/" + reading.getStatusLabel(),
                        "最老一条已等待 " + reading.getOldestAgeMillis() + "ms，超过阈值 "
                                + PENDING_ALERT_AGE_MILLIS + "ms"
                ));
            }
            if (SOURCE_OUTBOX.equals(reading.getSource()) && reading.getStatus() == 3 && count > 0) {
                alarms.add(new Alarm(
                        "OUTBOX_FAILED",
                        "ALERT",
                        "OUTBOX/FAILED",
                        "存在 " + count + " 条 FAILED Outbox（>=10001 时为封顶值），需要人工处置"
                ));
            }
            if (SOURCE_CONSUMED.equals(reading.getSource()) && reading.getStatus() == 3 && count > 0) {
                alarms.add(new Alarm(
                        "REPAIR_REQUIRED",
                        "ALERT",
                        "CONSUMED/REPAIR_REQUIRED",
                        "存在 " + count + " 条 status=3 待修复事件（>=10001 时为封顶值），需要人工处置"
                ));
            }
        }
        for (SchedulerReading reading : schedulerReadings) {
            if (reading.isRoleEnabledOnThisInstance()
                    && STATE_STALLED.equals(reading.getState())) {
                alarms.add(new Alarm(
                        "SCHEDULER_STALLED",
                        "ALERT",
                        reading.getOperation(),
                        "关键调度心跳已 " + reading.getAgeMillis() + "ms 未更新，超过阈值 "
                                + SCHEDULER_ALERT_AGE_MILLIS + "ms"
                ));
            }
        }
        return alarms;
    }

    private Snapshot failedSnapshot(long now, String lastError) {
        Snapshot previous = lastSuccess;
        if (previous == null) {
            return new Snapshot(
                    STATE_UNKNOWN,
                    true,
                    now,
                    null,
                    lastError,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
        // 保留上次成功数值，但明确标记 stale：绝不把失败写成 0。
        return new Snapshot(
                STATE_STALE,
                true,
                now,
                previous.getSampledAtEpochMillis(),
                lastError,
                previous.getDiagnostics(),
                previous.getBacklog(),
                previous.getSchedulerHeartbeats(),
                previous.getAlarms()
        );
    }

    private Snapshot degradedSnapshot(long now, List<String> diagnostics) {
        Snapshot previous = lastSuccess;
        return new Snapshot(
                STATE_DEGRADED,
                true,
                now,
                previous == null ? null : previous.getSampledAtEpochMillis(),
                "缺少 T05 观测索引，已跳过计数查询",
                diagnostics,
                previous == null ? List.of() : previous.getBacklog(),
                previous == null ? List.of() : previous.getSchedulerHeartbeats(),
                previous == null ? List.of() : previous.getAlarms()
        );
    }

    private Snapshot unknownSnapshot(long now) {
        return new Snapshot(
                STATE_UNKNOWN,
                true,
                now,
                null,
                "尚未完成第一次采样",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private boolean isRelayRoleEnabled() {
        return properties != null
                && properties.isAsyncEnabled()
                && properties.isOutboxRelayEnabled();
    }

    private boolean isSchedulingRoleEnabled() {
        return properties != null
                && properties.isAsyncEnabled()
                && properties.isSchedulingEnabled();
    }

    /**
     * 异常信息只保留异常类型：HTTP 响应与日志都不得出现 SQL 文本、payload
     * 或连接凭证。异常消息可能内嵌参数，不能截断后继续外露。
     */
    private String sanitize(RuntimeException e) {
        return e.getClass().getSimpleName();
    }

    /** 单条 backlog 读数；count 为 null 表示“未知”，0 才表示真的为空。 */
    public static final class BacklogReading {
        private final String source;
        private final int status;
        private final String statusLabel;
        private final Long count;
        private final Boolean countCapped;
        private final Long oldestAgeMillis;

        BacklogReading(String source,
                       int status,
                       String statusLabel,
                       Long count,
                       Boolean countCapped,
                       Long oldestAgeMillis) {
            this.source = source;
            this.status = status;
            this.statusLabel = statusLabel;
            this.count = count;
            this.countCapped = countCapped;
            this.oldestAgeMillis = oldestAgeMillis;
        }

        public String getSource() {
            return source;
        }

        public int getStatus() {
            return status;
        }

        public String getStatusLabel() {
            return statusLabel;
        }

        public Long getCount() {
            return count;
        }

        public Boolean getCountCapped() {
            return countCapped;
        }

        public Long getOldestAgeMillis() {
            return oldestAgeMillis;
        }
    }

    /** 关键调度心跳读数；与 backlog 一样区分 OK / STALLED / UNKNOWN。 */
    public static final class SchedulerReading {
        private final String operation;
        private final boolean roleEnabledOnThisInstance;
        private final Long lastHeartbeatAtEpochMillis;
        private final Long ageMillis;
        private final String state;

        SchedulerReading(String operation,
                         boolean roleEnabledOnThisInstance,
                         Long lastHeartbeatAtEpochMillis,
                         Long ageMillis,
                         String state) {
            this.operation = operation;
            this.roleEnabledOnThisInstance = roleEnabledOnThisInstance;
            this.lastHeartbeatAtEpochMillis = lastHeartbeatAtEpochMillis;
            this.ageMillis = ageMillis;
            this.state = state;
        }

        public String getOperation() {
            return operation;
        }

        public boolean isRoleEnabledOnThisInstance() {
            return roleEnabledOnThisInstance;
        }

        public Long getLastHeartbeatAtEpochMillis() {
            return lastHeartbeatAtEpochMillis;
        }

        public Long getAgeMillis() {
            return ageMillis;
        }

        public String getState() {
            return state;
        }
    }

    /** 告警项：code/severity/subject 都是有限常量，message 不含 eventId/vid。 */
    public static final class Alarm {
        private final String code;
        private final String severity;
        private final String subject;
        private final String message;

        Alarm(String code, String severity, String subject, String message) {
            this.code = code;
            this.severity = severity;
            this.subject = subject;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public String getSeverity() {
            return severity;
        }

        public String getSubject() {
            return subject;
        }

        public String getMessage() {
            return message;
        }
    }

    /** 采样快照：state/stale/lastSuccess 三件套表达“数值可信到什么程度”。 */
    public static final class Snapshot {
        private final String state;
        private final boolean stale;
        private final long sampledAtEpochMillis;
        private final Long lastSuccessAtEpochMillis;
        private final String lastError;
        private final List<String> diagnostics;
        private final List<BacklogReading> backlog;
        private final List<SchedulerReading> schedulerHeartbeats;
        private final List<Alarm> alarms;

        Snapshot(String state,
                 boolean stale,
                 long sampledAtEpochMillis,
                 Long lastSuccessAtEpochMillis,
                 String lastError,
                 List<String> diagnostics,
                 List<BacklogReading> backlog,
                 List<SchedulerReading> schedulerHeartbeats,
                 List<Alarm> alarms) {
            this.state = state;
            this.stale = stale;
            this.sampledAtEpochMillis = sampledAtEpochMillis;
            this.lastSuccessAtEpochMillis = lastSuccessAtEpochMillis;
            this.lastError = lastError;
            this.diagnostics = diagnostics;
            this.backlog = backlog;
            this.schedulerHeartbeats = schedulerHeartbeats;
            this.alarms = alarms;
        }

        public String getState() {
            return state;
        }

        public boolean isStale() {
            return stale;
        }

        public long getSampledAtEpochMillis() {
            return sampledAtEpochMillis;
        }

        public Long getLastSuccessAtEpochMillis() {
            return lastSuccessAtEpochMillis;
        }

        public String getLastError() {
            return lastError;
        }

        public List<String> getDiagnostics() {
            return diagnostics;
        }

        public List<BacklogReading> getBacklog() {
            return backlog;
        }

        public List<SchedulerReading> getSchedulerHeartbeats() {
            return schedulerHeartbeats;
        }

        public List<Alarm> getAlarms() {
            return alarms;
        }
    }
}
