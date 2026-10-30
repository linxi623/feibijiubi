package com.feibijiubi.backend.videosource.integration.applecms;

/**
 * E2-B：苹果CMS JSON 响应解析失败的固定异常。
 *
 * <p>只带一个固定原因码：{@code getMessage()} 是该原因码的固定文案，<b>不含</b>响应体片段、原始地址、
 * query、签名参数、上游 {@code msg} 文本或 Jackson 异常文案，也<b>不</b>携带 cause。原因很具体：Jackson
 * 的解析异常会把行列与原始片段拼进 message，{@code URISyntaxException} 会把原始 URL 拼进 message，
 * 把它们作为 cause 或文案向上透出，就等于把地址和签名写进日志/响应/审计。</p>
 *
 * <h2>它不说明什么</h2>
 * <p>本异常只表示"这份字节没有通过本适配器的解析契约"，<b>不</b>表示上游不可用、地址不可播放、内容不合法
 * 或作品不存在。反过来，同一份响应在更宽松的解析器里可能"能读"，那同样不是本适配器的契约：宁可明确失败，
 * 也不静默降级成 {@code 0}、空字符串或"当作已应用"。异常对象带正常堆栈，方便定位调用侧，但堆栈里不会
 * 出现响应体内容（解析器不记录它）。</p>
 */
public final class AppleCmsParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureCode code;

    /**
     * 只允许适配器自己构造：原因码由实现决定，调用方只读。
     *
     * @param code 固定原因码；文案取自 {@link FailureCode#description()}
     */
    AppleCmsParseException(FailureCode code) {
        super(code.description());
        this.code = code;
    }

    /** 固定的失败原因码。 */
    public FailureCode code() {
        return code;
    }

    /**
     * 固定的失败原因码。{@link #name()} 是对外稳定的机器码，{@link #description()} 是固定的中文文案，
     * 两者都不含任何响应体内容。
     */
    public enum FailureCode {

        /** 响应体为 {@code null} 或零字节。 */
        BODY_EMPTY("响应体为空"),

        /** 响应体超过 512 KiB 的解析上限（不解析、不截断）。 */
        BODY_TOO_LARGE("响应体超过 512 KiB 解析上限"),

        /** 响应体不是合法 JSON（含截断、非法转义、非法字面量）。 */
        MALFORMED_JSON("响应体不是合法 JSON"),

        /** 同一个对象里出现重复字段名（根对象、列表条目、详情条目以及被跳过的未知子树都检查）。 */
        DUPLICATE_FIELD("JSON 对象内出现重复字段名"),

        /** 根值之后还有第二个 JSON 值或多余内容。 */
        TRAILING_CONTENT("JSON 根值之后还有多余内容"),

        /** 嵌套深度超过 {@code MAX_JSON_DEPTH}。 */
        DEPTH_EXCEEDED("JSON 嵌套深度超过上限"),

        /** 触发 Jackson 的流读取约束（本适配器只收紧数值字面量长度）。 */
        RESOURCE_LIMIT_EXCEEDED("JSON 数值字面量或结构超出解析器限制"),

        /** 根节点不是 JSON 对象。 */
        ROOT_NOT_OBJECT("JSON 根节点不是对象"),

        /** 缺少 {@code code} 字段，或 {@code code} 的值不是 1。 */
        UPSTREAM_CODE_NOT_SUCCESS("上游 code 缺失或不是 1"),

        /** {@code code} 存在但类型不是整数/整数字符串。 */
        UPSTREAM_CODE_INVALID("上游 code 不是整数或整数字符串"),

        /** 缺少 {@code list} 字段或该字段为 JSON {@code null}。 */
        LIST_MISSING("缺少 list 字段"),

        /** {@code list} 不是数组。 */
        LIST_NOT_ARRAY("list 不是数组"),

        /** 单页条目数超过 100。 */
        LIST_TOO_LONG("单页 list 条目超过 100"),

        /** {@code list} 的元素不是对象。 */
        LIST_ITEM_NOT_OBJECT("list 元素不是 JSON 对象"),

        /** 作品 ID 不是正整数，也不是规范十进制字符串（或超过 191 位）。 */
        WORK_IDENTITY_INVALID("作品 vod_id 不是正整数或规范十进制字符串"),

        /** 缺少 {@code vod_name}，或它不是非空文本。 */
        TITLE_INVALID("标题 vod_name 缺失、为空或不是文本"),

        /** 文本快照超过该字段允许的长度（本适配器只拒绝，不截断）。 */
        SNAPSHOT_TEXT_TOO_LONG("文本快照超过该字段允许的长度"),

        /** 分类字段不是规范非负整数（或超过 20 位）。 */
        CATEGORY_INVALID("分类 type_id / type_name 不是规范值"),

        /** 上游 {@code class} 字段存在但不是数组。 */
        CLASS_LIST_INVALID("上游 class 不是数组"),

        /** 上游 {@code class} 分类列表超过 256 条。 */
        CLASS_LIST_TOO_LONG("上游 class 分类列表超过 256 条"),

        /** 缺少 {@code page} / {@code pagecount} / {@code limit} / {@code total} 之一。 */
        PAGINATION_MISSING("缺少分页字段"),

        /** 分页字段不是规范非负整数（小数、负数、前导零、首尾空白、科学计数、溢出均拒绝）。 */
        PAGINATION_INVALID("分页字段不是规范非负整数"),

        /** 详情 {@code list} 不是"恰好一条作品"。 */
        DETAIL_ITEM_COUNT_INVALID("详情 list 必须恰好包含一条作品"),

        /** 缺少 {@code vod_play_from} 或 {@code vod_play_url}。 */
        PLAY_LINES_MISSING("缺少 vod_play_from 或 vod_play_url"),

        /** {@code vod_play_from} / {@code vod_play_url} 不是文本。 */
        PLAY_LINES_INVALID("vod_play_from / vod_play_url 不是文本"),

        /** 线路字段整体为空，没有可解析的线路。 */
        NO_PLAY_LINES("没有可解析的播放线路"),

        /** 线路数超过 16。 */
        TOO_MANY_PLAY_LINES("线路数超过 16"),

        /** {@code vod_play_from} 与 {@code vod_play_url} 按 {@code $$$} 分段后数量不一致。 */
        PLAY_LINE_COUNT_MISMATCH("vod_play_from 与 vod_play_url 的线路段数不一致"),

        /** 线路键为空、含首尾空白或超过 191 字符。 */
        LINE_KEY_INVALID("线路键为空、含首尾空白或超过 191 字符"),

        /** 同一详情里出现重复线路键。 */
        DUPLICATE_LINE_KEY("线路键重复"),

        /** 一条线路的分组文本为空，没有任何分集条目。 */
        EMPTY_LINE_GROUP("线路没有分集条目"),

        /** 分集标签为空（含尾部分隔产生的空段）。 */
        EMPTY_EPISODE_LABEL("分集标签为空"),

        /** 分集地址为空，或该段没有 {@code $} 分隔符因此没有地址。 */
        EMPTY_EPISODE_ADDRESS("分集地址为空"),

        /** 单线路条目数超过 1000。 */
        TOO_MANY_EPISODES("单线路分集条目超过 1000"),

        /** 分集地址超过 2048 字符（不截断）。 */
        EPISODE_ADDRESS_TOO_LONG("分集地址超过 2048 字符"),

        /** 分集地址未通过注入的 {@code ExternalVideoUrlPolicy}。 */
        EPISODE_ADDRESS_INVALID("分集地址未通过 URL 语法与允许端点校验");

        private final String description;

        FailureCode(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }
}
