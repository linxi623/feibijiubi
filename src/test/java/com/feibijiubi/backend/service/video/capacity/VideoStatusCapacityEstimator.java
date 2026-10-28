package com.feibijiubi.backend.service.video.capacity;

/**
 * T14-A 容量估算的纯计算部分：不连接 MySQL/Redis，不写任何存储，可在无基础设施时单测。
 *
 * <p>口径约束（与 {@code docs/tutorial/video-status-capacity-audit.md} 一致）：</p>
 * <ul>
 *   <li>数据不足时一律返回 {@link Range#status()} = {@code UNKNOWN} 且 low/high 为 {@code null}，
 *       绝不回落成 0 —— “0 B/天”或“0 天耗尽”会把“没测到”伪装成“不会增长”。</li>
 *   <li>唯一允许的输入是<b>配置假设</b>（假设日事件量、假设保留天数）与
 *       {@code information_schema} 估算误差带；报告里必须标为假设，不能当作实测增长。</li>
 *   <li>MySQL 的 {@code TABLE_ROWS}/{@code DATA_LENGTH}/{@code INDEX_LENGTH} 是估算值
 *       （InnoDB 行数可偏差 40%–50%），所以 DB 日增只给区间，不给“精确值”。</li>
 * </ul>
 */
final class VideoStatusCapacityEstimator {

    /**
     * DB 日增区间下界系数。理由：{@code information_schema} 的体积与行数是估算，
     * InnoDB 的 {@code TABLE_ROWS} 在文档中就可能偏差 40%–50%，payload 长度分布也未知。
     */
    static final double DB_ESTIMATE_LOW_FACTOR = 0.5;

    /** DB 日增区间上界系数，与 {@link #DB_ESTIMATE_LOW_FACTOR} 同一理由（对称取 1.5）。 */
    static final double DB_ESTIMATE_HIGH_FACTOR = 1.5;

    /** processed key 样本少于该数量时，单键字节数一律 UNKNOWN（样本不足不给数字）。 */
    static final int MIN_PROCESSED_SAMPLE_KEYS = 5;

    private VideoStatusCapacityEstimator() {
    }

    /**
     * 估算输入。可测字段来自只读采样，假设字段必须显式标注。
     *
     * @param assumedDailyEvents        假设日事件量（0 = 未提供，按假设未定处理）
     * @param outboxBytesPerRow         由 information_schema 折算的 outbox 每行（含索引）字节，null = 未知
     * @param consumedBytesPerRow       由 information_schema 折算的 consumed 每行（含索引）字节，null = 未知
     * @param processedKeySampleMinBytes processed key 抽样单键字节最小值，null = 未知
     * @param processedKeySampleMaxBytes processed key 抽样单键字节最大值，null = 未知
     * @param processedKeySampleCount   实际抽样到的 processed key 数量
     * @param usedMemoryBytes           Redis used_memory，null = 未知
     * @param maxMemoryBytes            Redis maxmemory，null = 未知，0 = 未设置上限（不限）
     * @param assumedRetentionDays      假设保留天数（0 = 保留期未决策）
     */
    record Inputs(
            long assumedDailyEvents,
            Long outboxBytesPerRow,
            Long consumedBytesPerRow,
            Long processedKeySampleMinBytes,
            Long processedKeySampleMaxBytes,
            int processedKeySampleCount,
            Long usedMemoryBytes,
            Long maxMemoryBytes,
            int assumedRetentionDays) {
    }

    /** 一个带状态的结果区间：UNKNOWN 时 low/high 必须为 null，不允许用 0 占位。 */
    record Range(Long low, Long high, String status, String note) {

        static final String ESTIMATED = "ESTIMATED";
        static final String UNKNOWN = "UNKNOWN";

        Range {
            if (UNKNOWN.equals(status) && (low != null || high != null)) {
                throw new IllegalArgumentException("UNKNOWN range must not carry numbers");
            }
            if (ESTIMATED.equals(status) && (low == null || high == null || low > high)) {
                throw new IllegalArgumentException("ESTIMATED range needs low <= high");
            }
        }

        static Range unknown(String note) {
            return new Range(null, null, UNKNOWN, note);
        }

        boolean isUnknown() {
            return UNKNOWN.equals(status);
        }

        /** 报告用渲染：UNKNOWN 永远显示原因，不显示 0。 */
        String format(String unit) {
            if (isUnknown()) {
                return "UNKNOWN（" + note + "）";
            }
            return low + " " + unit + " ～ " + high + " " + unit + "（" + note + "）";
        }
    }

    /**
     * DB 日增字节：假设日事件量 × (outbox 每行字节 + consumed 每行字节)，再套估算误差带。
     *
     * <p>任一行字节未知时整体 UNKNOWN，不把“只知道 outbox”当成总增长。</p>
     */
    static Range dbDailyBytes(Inputs inputs) {
        if (inputs.assumedDailyEvents() <= 0) {
            return Range.unknown("假设日事件量未提供（-DvideoStatusCapacityAssumedDailyEvents），"
                    + "按假设未定处理，不给 0");
        }
        if (inputs.outboxBytesPerRow() == null) {
            return Range.unknown("video_status_outbox 无可用的行数/体积估算，折算不出每行字节");
        }
        if (inputs.consumedBytesPerRow() == null) {
            return Range.unknown("video_status_consumed_event 无可用的行数/体积估算，折算不出每行字节");
        }
        Long perEvent = add(inputs.outboxBytesPerRow(), inputs.consumedBytesPerRow());
        if (perEvent == null) {
            return Range.unknown("单事件字节相加溢出 long");
        }
        Long point = multiply(inputs.assumedDailyEvents(), perEvent);
        if (point == null) {
            return Range.unknown("日增字节计算溢出 long");
        }
        Long low = scaled(point, DB_ESTIMATE_LOW_FACTOR);
        Long high = scaled(point, DB_ESTIMATE_HIGH_FACTOR);
        if (low == null || high == null) {
            return Range.unknown("区间缩放溢出 long");
        }
        return new Range(low, high, Range.ESTIMATED,
                "点估计 " + point + " B/天；区间=点估计×" + DB_ESTIMATE_LOW_FACTOR + "～×"
                        + DB_ESTIMATE_HIGH_FACTOR + "（information_schema 估算误差配置假设，非实测）");
    }

    /**
     * Redis processed key 日增字节：假设日事件量 × 单键实测字节（抽样 min～max）。
     *
     * <p>每个事件写一个 processed key，所以日事件量就是日新增 key 数；但 key 数本身是假设，
     * 单键字节来自真实抽样。</p>
     */
    static Range processedDailyBytes(Inputs inputs) {
        if (inputs.processedKeySampleCount() < MIN_PROCESSED_SAMPLE_KEYS
                || inputs.processedKeySampleMinBytes() == null
                || inputs.processedKeySampleMaxBytes() == null) {
            return Range.unknown("processed key 样本不足（需要至少 " + MIN_PROCESSED_SAMPLE_KEYS
                    + " 个，实际 " + inputs.processedKeySampleCount() + " 个）");
        }
        if (inputs.assumedDailyEvents() <= 0) {
            return Range.unknown("假设日事件量未提供（-DvideoStatusCapacityAssumedDailyEvents），"
                    + "按假设未定处理，不给 0");
        }
        Long low = multiply(inputs.assumedDailyEvents(), inputs.processedKeySampleMinBytes());
        Long high = multiply(inputs.assumedDailyEvents(), inputs.processedKeySampleMaxBytes());
        if (low == null || high == null) {
            return Range.unknown("Redis 日增字节计算溢出 long");
        }
        return new Range(low, high, Range.ESTIMATED,
                "单键实测样本 " + inputs.processedKeySampleMinBytes() + "～"
                        + inputs.processedKeySampleMaxBytes() + " B/键，日事件量为配置假设");
    }

    /**
     * processed key 数量估算：假设日事件量 × 假设保留天数。
     *
     * <p>保留天数未决策时为 UNKNOWN —— 本工具不自行设定生产保留期。</p>
     */
    static Range processedKeyCount(Inputs inputs) {
        if (inputs.assumedDailyEvents() <= 0) {
            return Range.unknown("假设日事件量未提供（-DvideoStatusCapacityAssumedDailyEvents）");
        }
        if (inputs.assumedRetentionDays() <= 0) {
            return Range.unknown("保留天数未决策（-DvideoStatusCapacityAssumedRetentionDays），"
                    + "本工具不自行设定生产保留期");
        }
        Long count = multiply(inputs.assumedDailyEvents(), inputs.assumedRetentionDays());
        if (count == null) {
            return Range.unknown("key 数量计算溢出 long");
        }
        return new Range(count, count, Range.ESTIMATED,
                "假设日事件量 × 假设保留天数（两项都是配置假设，不是测量）");
    }

    /** processed key 保留占用字节：日增区间 × 假设保留天数。 */
    static Range processedRetainedBytes(Inputs inputs) {
        Range daily = processedDailyBytes(inputs);
        if (daily.isUnknown()) {
            return Range.unknown("Redis 日增未知：" + daily.note());
        }
        if (inputs.assumedRetentionDays() <= 0) {
            return Range.unknown("保留天数未决策（-DvideoStatusCapacityAssumedRetentionDays），"
                    + "本工具不自行设定生产保留期");
        }
        Long low = multiply(daily.low(), inputs.assumedRetentionDays());
        Long high = multiply(daily.high(), inputs.assumedRetentionDays());
        if (low == null || high == null) {
            return Range.unknown("保留占用计算溢出 long");
        }
        return new Range(low, high, Range.ESTIMATED,
                "日增区间 × 假设保留天数；保留期是假设，且未含 current/dirty/hot 增长");
    }

    /**
     * Redis 预计耗尽天数：可用内存 ÷ processed key 日增字节。
     *
     * <p>只折算 processed key 增长，不含 current/dirty/hot 与碎片；{@code maxmemory=0}
     * 表示未设置上限，因此无法给出耗尽天数（UNKNOWN，而不是 0）。</p>
     */
    static Range redisExhaustionDays(Inputs inputs) {
        if (inputs.maxMemoryBytes() == null) {
            return Range.unknown("未读到 maxmemory（CONFIG GET 失败或被禁用）");
        }
        if (inputs.maxMemoryBytes() == 0) {
            return Range.unknown("maxmemory=0 表示未设置上限，无法估算耗尽");
        }
        if (inputs.usedMemoryBytes() == null) {
            return Range.unknown("未读到 used_memory（INFO memory 失败）");
        }
        Range daily = processedDailyBytes(inputs);
        if (daily.isUnknown()) {
            return Range.unknown("Redis 日增未知：" + daily.note());
        }
        if (daily.low() == null || daily.low() <= 0) {
            return Range.unknown("Redis 日增下界为 0，无法折算天数");
        }
        long free = inputs.maxMemoryBytes() - inputs.usedMemoryBytes();
        if (free < 0) {
            free = 0;
        }
        long worstCaseDays = free / daily.high();
        long bestCaseDays = free / daily.low();
        return new Range(worstCaseDays, bestCaseDays, Range.ESTIMATED,
                "可用内存 " + free + " B ÷ processed 日增；未含 current/dirty/hot 与碎片");
    }

    private static Long add(Long left, Long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private static Long multiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private static Long scaled(long point, double factor) {
        double value = point * factor;
        if (value > Long.MAX_VALUE) {
            return null;
        }
        return Math.max(0L, Math.round(value));
    }
}
