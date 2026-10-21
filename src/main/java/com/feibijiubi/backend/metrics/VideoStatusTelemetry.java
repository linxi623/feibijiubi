package com.feibijiubi.backend.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 视频统计链路的最小内存遥测（T05）。
 *
 * <p>设计约束（详见 {@code docs/tutorial/video-status-observability.md}）：</p>
 * <ul>
 *   <li>只用 JDK 有限枚举做 key：{@link Operation} × {@link Outcome}，
 *       绝不把 eventId / vid / 业务字符串当标签，避免无界基数；</li>
 *   <li>纯本地内存累加（{@link LongAdder}），不加锁、不阻塞业务线程，
 *       也不引入新的监控依赖或暴露未鉴权 actuator 端点；</li>
 *   <li>只读观测：本类不写 Redis、不改事件状态、不参与幂等判断。
 *       埋点方只能在业务动作完成（提交成功/失败）后调用。</li>
 * </ul>
 *
 * <p>多实例语义：这里的计数是<b>本实例</b>的 operation 计数，不是全局值。
 * 全局积压要看共享数据库的 backlog 采样
 * （{@link VideoStatusBacklogSampler}），两者必须分开解读。</p>
 */
@Component
public class VideoStatusTelemetry {

    /** 链路阶段。新增阶段必须显式加在这里，保证指标 key 始终有限可枚举。 */
    public enum Operation {
        PUBLISH,
        FLUSH,
        CONSUME,
        RETRY_FORWARD,
        DEAD_FORWARD,
        LOCK,
        REBUILD,
        RELAY_TICK,
        FLUSH_TICK,
        RECOVERY_TICK
    }

    /** 结果分类：成功、失败、延后（可重试）、超时（结果不确定）。 */
    public enum Outcome {
        SUCCESS,
        FAILURE,
        DEFERRED,
        TIMEOUT
    }

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<Operation, OperationMetrics> operations =
            new ConcurrentHashMap<>();
    private final EndToEndMetrics endToEnd = new EndToEndMetrics();

    /** 生产构造：两个构造器必须显式标注，否则 Spring 找不到默认构造会启动失败。 */
    @Autowired
    public VideoStatusTelemetry(ObjectMapper objectMapper) {
        // 业务用 LocalDateTime.now() 产生主机时区时间；默认时钟必须是系统时区，
        // 否则非 UTC 主机会解析出 8 小时偏差（负值再被按 0 处理，伪装成健康）。
        this(objectMapper, Clock.systemDefaultZone());
    }

    /**
     * 测试可注入固定时钟（可指定非 UTC 时区），用来验证“创建 → FLUSHED”
     * 端到端延迟，不依赖 sleep 碰运气。
     */
    public VideoStatusTelemetry(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 记录一次 operation 的结果和耗时。
     *
     * @param elapsedNanos 该 operation 的本地耗时；负数按 0 处理
     */
    public void record(Operation operation, Outcome outcome, long elapsedNanos) {
        if (operation == null || outcome == null) {
            return;
        }
        operations.computeIfAbsent(operation, key -> new OperationMetrics())
                .record(outcome, elapsedNanos);
    }

    /**
     * 关键调度心跳。调度器每完成一轮（无论是否处理到数据）都必须调用，
     * 否则积压采样无法区分“没有数据”和“调度已停”。
     */
    public void heartbeat(Operation operation) {
        if (operation == null) {
            return;
        }
        operations.computeIfAbsent(operation, key -> new OperationMetrics())
                .heartbeat(clock.millis());
    }

    /**
     * 端到端延迟：解析事件 payload 里的 {@code occurredAt}，
     * 计算“事件创建 → 落库提交”的毫秒数。
     *
     * <p><b>只能在 MySQL 事务提交成功后调用</b>：提交失败或回滚时调用会
     * 把没落库的事件算进成功延迟。payload 无法解析时只累加 unknown 计数，
     * 不猜测、不返回假 0。</p>
     */
    public void recordFlushedPayload(String payload) {
        Long occurredAtMillis = parseOccurredAtMillis(payload);
        if (occurredAtMillis == null) {
            endToEnd.recordUnknown();
            return;
        }
        long elapsed = Math.max(0L, clock.millis() - occurredAtMillis);
        endToEnd.record(elapsed);
    }

    /** 只含有限枚举 key 的快照；调用方只能看到固定 10 个 operation。 */
    public Snapshot snapshot() {
        long now = clock.millis();
        List<OperationSnapshot> operationSnapshots = new ArrayList<>(Operation.values().length);
        for (Operation operation : Operation.values()) {
            OperationMetrics metrics = operations.get(operation);
            operationSnapshots.add(metrics == null
                    ? OperationSnapshot.empty(operation, now)
                    : metrics.snapshot(operation, now));
        }
        return new Snapshot(now, operationSnapshots, endToEnd.snapshot());
    }

    private Long parseOccurredAtMillis(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode occurredAt = root.path("occurredAt");
            if (occurredAt.isNumber()) {
                return occurredAt.asLong();
            }
            if (occurredAt.isTextual()) {
                return parseTextMillis(occurredAt.asText());
            }
            if (occurredAt.isArray() && occurredAt.size() >= 6) {
                return parseArrayMillis(occurredAt);
            }
        } catch (Exception ignored) {
            // 观测代码不允许把异常抛回业务链路：解析失败仅计 unknown。
            return null;
        }
        return null;
    }

    private Long parseTextMillis(String text) {
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException ignored) {
            // 继续尝试其它格式
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // 继续尝试本地时间
        }
        String normalized = value.indexOf(' ') > 0 ? value.replace(' ', 'T') : value;
        try {
            return LocalDateTime.parse(normalized)
                    .atZone(zone())
                    .toInstant()
                    .toEpochMilli();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Long parseArrayMillis(JsonNode node) {
        try {
            LocalDateTime local = LocalDateTime.of(
                    node.get(0).asInt(),
                    node.get(1).asInt(),
                    node.get(2).asInt(),
                    node.get(3).asInt(),
                    node.get(4).asInt(),
                    node.get(5).asInt(),
                    node.size() > 6 ? node.get(6).asInt() : 0
            );
            return local.atZone(zone()).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ZoneId zone() {
        return clock.getZone();
    }

    private static final class OperationMetrics {
        private final Map<Outcome, LongAdder> outcomes = new EnumMap<>(Outcome.class);
        private final LongAdder recorded = new LongAdder();
        private final LongAdder elapsedNanosTotal = new LongAdder();
        private final AtomicLong elapsedNanosMax = new AtomicLong();
        private final LongAdder heartbeatCount = new LongAdder();
        private final AtomicLong lastHeartbeatMillis = new AtomicLong();

        private OperationMetrics() {
            for (Outcome outcome : Outcome.values()) {
                outcomes.put(outcome, new LongAdder());
            }
        }

        private void record(Outcome outcome, long elapsedNanos) {
            long elapsed = Math.max(0L, elapsedNanos);
            outcomes.get(outcome).increment();
            recorded.increment();
            elapsedNanosTotal.add(elapsed);
            elapsedNanosMax.accumulateAndGet(elapsed, Math::max);
        }

        private void heartbeat(long millis) {
            heartbeatCount.increment();
            lastHeartbeatMillis.accumulateAndGet(millis, Math::max);
        }

        private OperationSnapshot snapshot(Operation operation, long now) {
            Map<String, Long> outcomeCounts = new LinkedHashMap<>();
            for (Outcome outcome : Outcome.values()) {
                outcomeCounts.put(outcome.name(), outcomes.get(outcome).sum());
            }
            long lastHeartbeat = lastHeartbeatMillis.get();
            return new OperationSnapshot(
                    operation.name(),
                    outcomeCounts,
                    recorded.sum(),
                    elapsedNanosTotal.sum(),
                    elapsedNanosMax.get(),
                    heartbeatCount.sum(),
                    lastHeartbeat == 0L ? null : lastHeartbeat,
                    lastHeartbeat == 0L ? null : Math.max(0L, now - lastHeartbeat)
            );
        }
    }

    private static final class EndToEndMetrics {
        private final LongAdder samples = new LongAdder();
        private final LongAdder unknown = new LongAdder();
        private final LongAdder totalMillis = new LongAdder();
        private final AtomicLong maxMillis = new AtomicLong();
        private final AtomicLong lastMillis = new AtomicLong(-1L);

        private void record(long millis) {
            samples.increment();
            totalMillis.add(millis);
            maxMillis.accumulateAndGet(millis, Math::max);
            lastMillis.set(millis);
        }

        private void recordUnknown() {
            unknown.increment();
        }

        private EndToEndSnapshot snapshot() {
            long count = samples.sum();
            long last = lastMillis.get();
            return new EndToEndSnapshot(
                    count,
                    unknown.sum(),
                    count == 0 ? null : totalMillis.sum() / count,
                    count == 0 ? null : maxMillis.get(),
                    last < 0 ? null : last
            );
        }
    }

    /** 一个 operation 的只读快照。 */
    public static final class OperationSnapshot {
        private final String operation;
        private final Map<String, Long> outcomes;
        private final long recorded;
        private final long elapsedNanosTotal;
        private final long elapsedNanosMax;
        private final long heartbeatCount;
        private final Long lastHeartbeatAtEpochMillis;
        private final Long lastHeartbeatAgeMillis;

        OperationSnapshot(String operation,
                          Map<String, Long> outcomes,
                          long recorded,
                          long elapsedNanosTotal,
                          long elapsedNanosMax,
                          long heartbeatCount,
                          Long lastHeartbeatAtEpochMillis,
                          Long lastHeartbeatAgeMillis) {
            this.operation = operation;
            this.outcomes = outcomes;
            this.recorded = recorded;
            this.elapsedNanosTotal = elapsedNanosTotal;
            this.elapsedNanosMax = elapsedNanosMax;
            this.heartbeatCount = heartbeatCount;
            this.lastHeartbeatAtEpochMillis = lastHeartbeatAtEpochMillis;
            this.lastHeartbeatAgeMillis = lastHeartbeatAgeMillis;
        }

        static OperationSnapshot empty(Operation operation, long now) {
            Map<String, Long> outcomeCounts = new LinkedHashMap<>();
            for (Outcome outcome : Outcome.values()) {
                outcomeCounts.put(outcome.name(), 0L);
            }
            return new OperationSnapshot(
                    operation.name(),
                    outcomeCounts,
                    0L,
                    0L,
                    0L,
                    0L,
                    null,
                    null
            );
        }

        public String getOperation() {
            return operation;
        }

        public Map<String, Long> getOutcomes() {
            return outcomes;
        }

        public long getRecorded() {
            return recorded;
        }

        public long getElapsedNanosTotal() {
            return elapsedNanosTotal;
        }

        public long getElapsedNanosMax() {
            return elapsedNanosMax;
        }

        public long getHeartbeatCount() {
            return heartbeatCount;
        }

        public Long getLastHeartbeatAtEpochMillis() {
            return lastHeartbeatAtEpochMillis;
        }

        public Long getLastHeartbeatAgeMillis() {
            return lastHeartbeatAgeMillis;
        }
    }

    /** 端到端延迟快照；没有样本时 avg/max/last 为 null，而不是假 0。 */
    public static final class EndToEndSnapshot {
        private final long samples;
        private final long unknown;
        private final Long avgMillis;
        private final Long maxMillis;
        private final Long lastMillis;

        EndToEndSnapshot(long samples,
                         long unknown,
                         Long avgMillis,
                         Long maxMillis,
                         Long lastMillis) {
            this.samples = samples;
            this.unknown = unknown;
            this.avgMillis = avgMillis;
            this.maxMillis = maxMillis;
            this.lastMillis = lastMillis;
        }

        public long getSamples() {
            return samples;
        }

        public long getUnknown() {
            return unknown;
        }

        public Long getAvgMillis() {
            return avgMillis;
        }

        public Long getMaxMillis() {
            return maxMillis;
        }

        public Long getLastMillis() {
            return lastMillis;
        }
    }

    /** 本实例遥测的完整只读快照。 */
    public static final class Snapshot {
        private final long sampledAtEpochMillis;
        private final List<OperationSnapshot> operations;
        private final EndToEndSnapshot endToEnd;

        Snapshot(long sampledAtEpochMillis,
                 List<OperationSnapshot> operations,
                 EndToEndSnapshot endToEnd) {
            this.sampledAtEpochMillis = sampledAtEpochMillis;
            this.operations = operations;
            this.endToEnd = endToEnd;
        }

        public long getSampledAtEpochMillis() {
            return sampledAtEpochMillis;
        }

        public List<OperationSnapshot> getOperations() {
            return operations;
        }

        public EndToEndSnapshot getEndToEnd() {
            return endToEnd;
        }
    }
}
