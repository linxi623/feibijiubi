package com.feibijiubi.backend.videostats.capacity;

import java.util.List;

/**
 * T14-A 只读容量核查 + 归档 dry-run 的报告模型与文本渲染。
 *
 * <p>报告刻意只包含：表名/索引名、{@code information_schema} 估算体积、终态候选的
 * <b>id + 时间 + 状态</b>、Redis 内存指标与 processed key 抽样字节数。它<b>不含</b>连接串、
 * 主机、账号、密码、完整 eventId、payload 或 {@code last_error} —— 打印/贴出报告不会泄露凭证或事件内容。</p>
 *
 * <p>所有终态候选一律标 {@link #DELETION_NOT_AUTHORIZED}：保留期决策未定，且没有“旧消息硬拒绝/去重墓碑”，
 * “processed 还有 TTL”也不能证明删除 DB 幂等证据安全。</p>
 */
final class VideoStatusCapacityReport {

    static final String MODE_READ_ONLY_DRY_RUN = "READ_ONLY_DRY_RUN";
    static final String DELETION_NOT_AUTHORIZED = "NOT_AUTHORIZED_FOR_DELETION";
    static final String RETENTION_PERIOD_UNDECIDED = "RETENTION_PERIOD_UNDECIDED";
    static final String WINDOW_EVALUABLE = "EVALUABLE";
    static final String WINDOW_NOT_EVALUABLE = "NOT_EVALUABLE";
    static final String SAMPLE_STATUS_SAMPLED = "SAMPLED";
    static final String SAMPLE_STATUS_UNKNOWN = "UNKNOWN";

    /** 表体积估算（{@code information_schema.tables}）：行数与字节都是估算，不是 COUNT(*)。 */
    record TableSize(String table, Long estimatedRows, Long dataBytes, Long indexBytes, Long totalBytes) {
    }

    /** dry-run 候选：只有 id/时间/状态，没有 payload、last_error、eventId。 */
    record TerminalCandidate(String table, long id, String timestamp, int status, String deletionAuthorization) {
    }

    /** 一个终态窗口的评估结果；NOT_EVALUABLE 时候选为空，且不降级为全表扫描。 */
    record CandidateWindow(String table, String status, String requiredIndex, String accessPath,
                           List<TerminalCandidate> candidates, boolean truncated, String reason) {
    }

    record RedisMemory(Long usedMemoryBytes, Long usedMemoryPeakBytes, Long maxMemoryBytes,
                       String maxMemoryPolicy, Long dbSize, String note) {
    }

    /**
     * SCAN 预算与实际命令次数。{@code countHint} 只是发给 Redis 的 {@code COUNT}（不是单页硬上限）；
     * 真正有界的是 {@code scanCommands <= scanCommandLimit}，采样数量另有 {@code sampleKeyLimit}。
     */
    record ScanBudget(int scanCommands, int scanCommandLimit, int sampleKeyLimit, int countHint) {
    }

    /**
     * processed key 抽样结果。{@code scanComplete=false} 表示游标未归零、覆盖被截断：
     * 已采样字节仍是真实测量值，但“全库有没有更多/更大 processed key”只能标为 UNKNOWN。
     */
    record ProcessedKeySample(int sampledKeys, int returnedKeys, ScanBudget budget, boolean scanComplete,
                              Long minBytes, Long maxBytes, Long averageBytes, String status, String note) {
    }

    /** 一条估算：区间 + 单位 + 种类（ESTIMATED / UNKNOWN）。 */
    record Estimate(String label, VideoStatusCapacityEstimator.Range range, String unit) {
    }

    private final String generatedAt;
    private final List<TableSize> tables;
    private final List<CandidateWindow> windows;
    private final RedisMemory memory;
    private final ProcessedKeySample processedSample;
    private final VideoStatusCapacityEstimator.Inputs inputs;
    private final List<Estimate> estimates;
    private final List<String> uncertainties;
    private final int windowDays;
    private final int candidateLimit;

    VideoStatusCapacityReport(String generatedAt,
                              List<TableSize> tables,
                              List<CandidateWindow> windows,
                              RedisMemory memory,
                              ProcessedKeySample processedSample,
                              VideoStatusCapacityEstimator.Inputs inputs,
                              List<Estimate> estimates,
                              List<String> uncertainties,
                              int windowDays,
                              int candidateLimit) {
        this.generatedAt = generatedAt;
        this.tables = List.copyOf(tables);
        this.windows = List.copyOf(windows);
        this.memory = memory;
        this.processedSample = processedSample;
        this.inputs = inputs;
        this.estimates = List.copyOf(estimates);
        this.uncertainties = List.copyOf(uncertainties);
        this.windowDays = windowDays;
        this.candidateLimit = candidateLimit;
    }

    String generatedAt() {
        return generatedAt;
    }

    List<TableSize> tables() {
        return tables;
    }

    List<CandidateWindow> windows() {
        return windows;
    }

    RedisMemory memory() {
        return memory;
    }

    ProcessedKeySample processedSample() {
        return processedSample;
    }

    VideoStatusCapacityEstimator.Inputs inputs() {
        return inputs;
    }

    List<Estimate> estimates() {
        return estimates;
    }

    List<String> uncertainties() {
        return uncertainties;
    }

    /** 本工具永远只读：没有 DELETE/UPDATE/INSERT/DDL，也没有 Redis 写命令或配置修改。 */
    boolean writePerformed() {
        return false;
    }

    /** 本工具永远不授权删除，即使候选是安全终态。 */
    boolean deletionAuthorized() {
        return false;
    }

    String render() {
        StringBuilder out = new StringBuilder(4096);
        out.append("=== 视频统计容量核查（T14-A：只读核查 + 归档 dry-run）===\n");
        out.append("生成时间（数据库 NOW(3)）: ").append(generatedAt).append('\n');
        out.append("模式: ").append(MODE_READ_ONLY_DRY_RUN).append('\n');
        out.append("写入: 未执行任何写操作（writePerformed=false）\n");
        out.append("删除授权: 未授权（候选全部 ").append(DELETION_NOT_AUTHORIZED)
                .append("；保留期决策: ").append(RETENTION_PERIOD_UNDECIDED).append("）\n");
        out.append("统计口径: MySQL information_schema 的 TABLE_ROWS/DATA_LENGTH/INDEX_LENGTH 是估算值")
                .append("（InnoDB 行数可偏差 40%–50%）；本工具不执行 COUNT(*) 全表统计。\n");

        out.append("\n[1] 表与索引体积（估算）\n");
        out.append(String.format("%-32s %12s %16s %16s %16s%n",
                "table", "est_rows", "data_bytes", "index_bytes", "total_bytes"));
        for (TableSize table : tables) {
            out.append(String.format("%-32s %12s %16s %16s %16s%n",
                    table.table(), orUnknown(table.estimatedRows()),
                    orUnknown(table.dataBytes()), orUnknown(table.indexBytes()),
                    orUnknown(table.totalBytes())));
        }

        out.append("\n[2] 终态候选 dry-run（只读；只列 id/时间/状态，不读取 payload/last_error/eventId）\n");
        out.append("窗口: 时间窗 = 早于 now-").append(windowDays).append(" 天（dry-run 可见窗口假设，不是保留期决策）")
                .append("；上限 = ").append(candidateLimit).append(" 行（LIMIT 上限+1 探测截断）\n");
        for (CandidateWindow window : windows) {
            out.append(window.table())
                    .append("  status=").append(window.status())
                    .append("  requiredIndex=").append(window.requiredIndex());
            if (window.accessPath() != null) {
                out.append("  plan=").append(window.accessPath());
            }
            if (window.reason() != null) {
                out.append("  reason=").append(window.reason());
            }
            out.append('\n');
            for (TerminalCandidate candidate : window.candidates()) {
                out.append("  id=").append(candidate.id())
                        .append("  time=").append(candidate.timestamp())
                        .append("  status=").append(candidate.status())
                        .append("  deletion=").append(candidate.deletionAuthorization())
                        .append('\n');
            }
            if (!window.candidates().isEmpty()) {
                out.append("  截断: ").append(window.truncated() ? "是（窗口外还有更多终态行，未继续读取）" : "否")
                        .append('\n');
            }
            out.append("  说明: status0(RECEIVED)/1(REDIS_APPLIED_PENDING_FLUSH)/3(REPAIR_REQUIRED) 与 ")
                    .append("Outbox FAILED 永不列为可删除候选；缺索引时报告 NOT_EVALUABLE 而不是全表扫描。\n");
        }

        out.append("\n[3] Redis 只读容量（INFO memory + CONFIG GET + 有界 SCAN/MEMORY USAGE）\n");
        out.append("used_memory=").append(orUnknown(memory.usedMemoryBytes())).append(" B")
                .append("  used_memory_peak=").append(orUnknown(memory.usedMemoryPeakBytes())).append(" B")
                .append("  dbsize=").append(orUnknown(memory.dbSize())).append('\n');
        out.append("maxmemory=").append(memory.maxMemoryBytes() == null
                        ? "UNKNOWN（CONFIG GET 未读到）"
                        : memory.maxMemoryBytes() == 0
                        ? "0（未设置上限，不限）"
                        : memory.maxMemoryBytes() + " B")
                .append("  maxmemory-policy=").append(memory.maxMemoryPolicy() == null
                        ? "UNKNOWN" : memory.maxMemoryPolicy())
                .append('\n');
        if (memory.note() != null) {
            out.append("说明: ").append(memory.note()).append('\n');
        }
        out.append("noeviction 告警建议: 达到 maxmemory 时写命令（含重建门闩 SET、processed SET、")
                .append("HSET/SADD/ZADD、outbox 状态回写）会直接报错，Redis 不会自动淘汰 key。")
                .append("处置顺序是扩容内存 / 降低写入 / 停止输入并排空，")
                .append("禁止删除重建门闩、禁止删 processed 幂等 key、禁止 FLUSHALL/FLUSHDB、")
                .append("禁止改 maxmemory-policy 来“腾空间”；门闩没有 TTL，删掉它只会破坏 token 所有权校验。\n");
        out.append("processed key 抽样: SCAN 命令 ").append(processedSample.budget().scanCommands())
                .append("/").append(processedSample.budget().scanCommandLimit())
                .append(" 次（COUNT=").append(processedSample.budget().countHint())
                .append(" 只是 hint，不是单页硬返回上限），返回 key ")
                .append(processedSample.returnedKeys())
                .append(" 个，命中样本 ").append(processedSample.sampledKeys())
                .append("/").append(processedSample.budget().sampleKeyLimit())
                .append(" 个，游标归零=").append(processedSample.scanComplete()
                        ? "是（遍历完 keyspace）" : "否（覆盖截断：未遍历完 keyspace）")
                .append("，status=").append(processedSample.status()).append('\n');
        if (SAMPLE_STATUS_SAMPLED.equals(processedSample.status())) {
            out.append("  单键字节: min=").append(processedSample.minBytes())
                    .append(" B  max=").append(processedSample.maxBytes())
                    .append(" B  avg=").append(processedSample.averageBytes()).append(" B\n");
            if (processedSample.note() != null) {
                out.append("  覆盖: ").append(processedSample.note()).append('\n');
            }
        } else {
            out.append("  单键字节: UNKNOWN（").append(processedSample.note()).append("）\n");
        }
        out.append("  有界口径: SCAN 命令次数才是硬上限（一次调用 = 一次 SCAN 往返），COUNT 只是 hint，")
                .append("空匹配页不代表遍历完成；MEMORY USAGE 采样数量另有独立上限。\n");
        out.append("  未统计 processed key 总数（禁止 KEYS/全量扫描）；只给有界抽样，覆盖截断或样本不足时显式 UNKNOWN。\n");

        out.append("\n[4] 容量公式与估算\n");
        out.append("假设: 日事件量=").append(inputs.assumedDailyEvents())
                .append("（-DvideoStatusCapacityAssumedDailyEvents，配置假设，不是测量）")
                .append("；保留天数=").append(inputs.assumedRetentionDays())
                .append("（-DvideoStatusCapacityAssumedRetentionDays，配置假设；生产保留期决策未定）\n");
        out.append("公式:\n");
        out.append("  DB 日增字节 ≈ 假设日事件量 × (outbox 平均行+索引字节 + consumed 平均行+索引字节)\n");
        out.append("  Redis processed 日增字节 ≈ 假设日事件量 × 单键实测字节（样本 min～max）\n");
        out.append("  processed key 数量 ≈ 假设日事件量 × 假设保留天数\n");
        out.append("  Redis processed 保留占用 ≈ Redis processed 日增 × 假设保留天数\n");
        out.append("  Redis 预计耗尽天数 ≈ (maxmemory - used_memory) ÷ Redis processed 日增\n");
        for (Estimate estimate : estimates) {
            out.append("  ").append(estimate.label()).append(": ")
                    .append(estimate.range().format(estimate.unit()))
                    .append("  [").append(estimate.range().status()).append("]\n");
        }
        out.append("说明: 区间只表达“配置假设 + 估算误差”的范围，不是实测增长；数据不足一律 UNKNOWN，不回落为 0。\n");

        out.append("\n[5] 未做 / 边界\n");
        out.append("- 本工具不删除、不归档、不改任何 DB 行、Redis key、TTL 或 Redis 配置；没有 HTTP 接口、")
                .append("没有生产调度器、没有新增 schema/迁移。\n");
        out.append("- 终态（outbox SENT=2 / consumed FLUSHED=2）也全部 ").append(DELETION_NOT_AUTHORIZED)
                .append("：最大重放窗口、DLQ 保留与审计期限、保留期都未决策，")
                .append("并且没有“旧消息硬拒绝/去重墓碑”，所以“processed 还有 TTL”不构成删除 DB 幂等证据的依据。\n");
        out.append("- 未来清理的前提：先决策保留期与重放窗口，再提供旧消息硬拒绝或去重墓碑，")
                .append("才可能讨论删除终态历史；本工具不提供任何清理入口。\n");
        if (!uncertainties.isEmpty()) {
            out.append("\n[6] 未确定项（UNKNOWN 原因）\n");
            for (String uncertainty : uncertainties) {
                out.append("- ").append(uncertainty).append('\n');
            }
        }
        return out.toString();
    }

    private static String orUnknown(Long value) {
        return value == null ? "UNKNOWN" : value.toString();
    }
}
