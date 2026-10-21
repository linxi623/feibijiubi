package com.feibijiubi.backend.common;

/**
 * 视频统计计数的唯一支持区间：{@code [0, Long.MAX_VALUE]}。
 *
 * <p>数据库列是 {@code BIGINT UNSIGNED}，可表示到 2^64-1，Java 只能到 2^63-1；
 * Redis {@code HINCRBY} 是有符号 64 位。因此应用层必须显式拒绝超过
 * {@link Long#MAX_VALUE} 的值，而不是让 MySQL 接受 2^63 后在读取时抛异常。</p>
 *
 * <p>负数一律不合法：取消互动必须先由业务状态证明存在对应正向计数，不能用
 * {@code GREATEST(..., 0)} 或 {@code MAX(..., 0)} 把非法取消静默篡改成 0。</p>
 */
public final class VideoStatusCounterRange {

    public static final long MIN_VALUE = 0L;
    public static final long MAX_VALUE = Long.MAX_VALUE;

    private VideoStatusCounterRange() {
    }

    /** 校验单个已解析的计数。 */
    public static long require(long value, String field) {
        if (value < MIN_VALUE) {
            throw new VideoStatusCounterRangeException(
                    field,
                    VideoStatusCounterRangeException.Reason.NEGATIVE,
                    "视频统计计数不能为负: field=" + field + ", value=" + value
            );
        }
        // long 自身不可能大于 Long.MAX_VALUE，上界在解析与累计处校验。
        return value;
    }

    /**
     * 更新单个计数值：把<b>有符号增量</b>应用到<b>非负当前值</b>，返回新的非负计数。
     *
     * <p>契约（与批次累计 {@code VideoStatusDelta#plus} 不同，后者是有符号的纯加法）：</p>
     * <ul>
     *   <li>{@code current} 必须已是合法计数，否则 {@code NEGATIVE}；</li>
     *   <li>{@code delta} 允许为负，用于抵销已存在的正向计数（例如取消点赞 / 取消收藏）；</li>
     *   <li><b>结果必须仍在 {@code [0, Long.MAX_VALUE]}</b>。{@code addExact(0, -1)} 属于非法输入，
     *       抛 {@code NEGATIVE}；{@code addExact(1, -1)} 合法并得到 0。任何情况下都不得
     *       clamp 成 0（{@code Math.max(0, ...)} 会把非法取消静默改成合法状态）；</li>
     *   <li>long 数学溢出抛 {@code OVERFLOW}，不静默回绕。</li>
     * </ul>
     *
     * <p>批次场景不要逐个调用本方法做“先减后加”：多个有符号增量应先用
     * {@code VideoStatusDelta#plus} 求和（内部只做有符号 {@code Math.addExact}，允许中间结果为负），
     * 再对最终计数做一次非负判定。</p>
     */
    public static long addExact(long current, long delta, String field) {
        require(current, field);

        long result;
        try {
            result = Math.addExact(current, delta);
        } catch (ArithmeticException e) {
            throw new VideoStatusCounterRangeException(
                    field,
                    VideoStatusCounterRangeException.Reason.OVERFLOW,
                    "视频统计计数累计溢出: field=" + field
                            + ", current=" + current + ", delta=" + delta,
                    e
            );
        }

        if (result < MIN_VALUE) {
            // 有符号增量把计数打到负值：输入本身不自洽，必须显式拒绝。
            throw new VideoStatusCounterRangeException(
                    field,
                    VideoStatusCounterRangeException.Reason.NEGATIVE,
                    "视频统计计数不能为负: field=" + field
                            + ", current=" + current
                            + ", delta=" + delta
                            + ", result=" + result
            );
        }
        return result;
    }

    /**
     * 解析十进制字符串（Redis Hash 的原始值、事件增量的字符串形式）。
     * 只接受可选的 {@code +}/{@code -} 加十进制数字；不接受小数、空白、科学计数法。
     */
    public static long parse(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new VideoStatusCounterRangeException(
                    field,
                    VideoStatusCounterRangeException.Reason.MISSING,
                    "视频统计计数字段缺失: field=" + field
            );
        }
        String value = raw.trim();
        int start = value.charAt(0) == '+' || value.charAt(0) == '-'
                ? 1
                : 0;
        if (start == value.length()) {
            throw malformed(field, raw);
        }
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw malformed(field, raw);
            }
        }
        try {
            return require(Long.parseLong(value), field);
        } catch (NumberFormatException e) {
            throw new VideoStatusCounterRangeException(
                    field,
                    VideoStatusCounterRangeException.Reason.OVERFLOW,
                    "视频统计计数超出 Long 范围: field=" + field
                            + ", value=" + raw,
                    e
            );
        }
    }

    private static VideoStatusCounterRangeException malformed(
            String field,
            String raw
    ) {
        return new VideoStatusCounterRangeException(
                field,
                VideoStatusCounterRangeException.Reason.MALFORMED,
                "视频统计计数不是十进制整数: field=" + field
                        + ", value=" + raw
        );
    }
}
