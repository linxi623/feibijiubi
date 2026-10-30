package com.feibijiubi.backend.videostats.exception;

/**
 * 视频统计计数超出 T02 约定的 {@code [0, Long.MAX_VALUE]} 范围，或取值本身不可解析。
 *
 * <p>该异常刻意不是 {@code RetryableMessageException}：同一份数据无条件重试不能变合法。
 * 调度层如何分类（暂态依赖 / 永久数据错误）由 T03 决定，这里只提供可判别的类型和原因。</p>
 *
 * <p>抛出点：{@link VideoStatusCounterRange#parse}（Redis/事件原始值解析）、
 * {@link VideoStatusCounterRange#require}（单个计数/DB 基线）、
 * {@link VideoStatusCounterRange#addExact}（有符号增量更新非负计数）。</p>
 */
public class VideoStatusCounterRangeException extends RuntimeException {

    public enum Reason {
        /** 字段缺失（例如 Redis Hash 被外部删除）。 */
        MISSING,
        /** 不是十进制整数（例如空串、小数、科学计数法、非法字符）。 */
        MALFORMED,
        /** 小于 0：解析值为负，或有符号增量把非负计数打成负值。 */
        NEGATIVE,
        /** 大于 Long.MAX_VALUE。 */
        OVERFLOW
    }

    private final String field;
    private final Reason reason;

    public VideoStatusCounterRangeException(
            String field,
            Reason reason,
            String message
    ) {
        super(message);
        this.field = field;
        this.reason = reason;
    }

    public VideoStatusCounterRangeException(
            String field,
            Reason reason,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.field = field;
        this.reason = reason;
    }

    public String getField() {
        return field;
    }

    public Reason getReason() {
        return reason;
    }
}
