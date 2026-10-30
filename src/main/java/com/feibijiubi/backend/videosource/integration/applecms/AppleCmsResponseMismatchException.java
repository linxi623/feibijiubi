package com.feibijiubi.backend.videosource.integration.applecms;

/**
 * E2-D：{@link AppleCmsSourceClient} 的<b>响应与请求不一致</b>固定异常。
 *
 * <p>它只回答一件事：<b>这份响应不是对那次请求的回答</b>（页码不同、条目分类不同、作品身份不同）。
 * 原因码是固定的机器码，{@code getMessage()} 是固定的中文文案，<b>不</b>包含响应体片段、标题、分类名、
 * 上游作品 ID、播放地址、query 或签名参数，也<b>不</b>携带 cause。理由与
 * {@link AppleCmsParseException} 相同：标题、分类名、线路键、地址都是上游原文，上游完全可以把 URL 或
 * 凭据塞进这些字段；把它们拼进异常，就等于把上游内容写进日志、响应与审计。</p>
 *
 * <h2>它不说明什么</h2>
 * <p>不一致<b>不等于</b>上游不可用、内容不合法、作品不存在或地址不可播放。反过来，页码与身份都一致也
 * <b>不</b>说明内容正确、已授权或可播放 —— 本异常只覆盖"响应是否对应这次请求"这一层，见
 * {@link AppleCmsSourceClient} 的边界说明。</p>
 */
public final class AppleCmsResponseMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureCode code;

    /**
     * 只允许 {@link AppleCmsSourceClient} 自己构造：原因码由实现决定，调用方只读。
     *
     * @param code 固定原因码；文案取自 {@link FailureCode#description()}
     */
    AppleCmsResponseMismatchException(FailureCode code) {
        super(code.description());
        this.code = code;
    }

    /** 固定的失败原因码。 */
    public FailureCode code() {
        return code;
    }

    /**
     * 固定的失败原因码。{@link #name()} 是对外稳定的机器码，{@link #description()} 是固定的中文文案，
     * 两者都不含任何上游文本。
     */
    public enum FailureCode {

        /** 列表响应自报的 {@code page} 与本次请求的页码不同。 */
        RESPONSE_PAGE_MISMATCH("响应自报页码与请求页码不一致"),

        /** 指定了分类过滤，但本页存在分类不同的条目（含缺少分类的条目）。 */
        RESPONSE_CATEGORY_MISMATCH("响应条目分类与请求的分类不一致"),

        /** 详情响应的作品身份与请求的作品 ID 不同。 */
        RESPONSE_IDENTITY_MISMATCH("响应作品身份与请求的作品 ID 不一致");

        private final String description;

        FailureCode(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }
}
