package com.feibijiubi.backend.integration.videosource.media;

/**
 * M1：HLS 媒体清单解析失败的固定异常。
 *
 * <p>只带一个固定原因码：{@code getMessage()} 是该原因码的固定文案，<b>不含</b>清单原文、原始行、分片地址、
 * {@code #EXT-X-KEY} 里的密钥地址或查询串，也<b>不</b>携带 cause。原因很具体：解码/解析类异常常把出错位置
 * 与原始片段拼进 message，把它作为 cause 或文案向上透出，就等于把上游地址与签名参数写进日志、响应或审计。</p>
 *
 * <h2>它不说明什么</h2>
 * <p>本异常只表示"这份字节没有通过本解析器的结构契约"，<b>不</b>表示上游不可用、清单内容非法、地址不可播放，
 * 也不表示时长不存在——同一份清单在更宽松的解析器里可能"能读"，那同样不是本解析器的契约：宁可明确失败，
 * 也不静默降级成 {@code 0}、跳过坏分片或"当作已应用"。异常对象带正常堆栈，方便定位调用侧，但堆栈里不会出现
 * 清单内容（解析器不记录它）。</p>
 */
public final class HlsManifestParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureCode code;

    /**
     * 只允许解析器自己构造：原因码由实现决定，调用方只读。
     *
     * @param code 固定原因码；文案取自 {@link FailureCode#description()}
     */
    HlsManifestParseException(FailureCode code) {
        super(code.description());
        this.code = code;
    }

    /** 固定的失败原因码。 */
    public FailureCode code() {
        return code;
    }

    /**
     * 固定的失败原因码。{@link #name()} 是对外稳定的机器码，{@link #description()} 是固定的中文文案，
     * 两者都不含任何清单内容。
     */
    public enum FailureCode {

        /** 输入为 {@code null} 或零字节。 */
        MANIFEST_EMPTY("清单为空"),

        /** 输入超过 512 KiB 的字节上限（不解析、不截断）。 */
        MANIFEST_TOO_LARGE("清单超过 512 KiB 字节上限"),

        /** 字节不是合法 UTF-8（严格解码，不做替换字符降级）。 */
        MANIFEST_NOT_UTF8("清单不是合法 UTF-8"),

        /** 清单以 UTF-8 BOM 开头；本解析器要求首行严格是 {@code #EXTM3U}。 */
        BOM_UNSUPPORTED("清单以 UTF-8 BOM 开头"),

        /** 清单含 NUL、C0 控制字符（制表符除外）或 C1 控制字符。 */
        CONTROL_CHARACTER_UNSUPPORTED("清单含 NUL 或不允许的控制字符"),

        /** 出现孤立的 {@code CR}（只支持 {@code LF} 与 {@code CRLF}）。 */
        LINE_ENDING_INVALID("清单出现孤立 CR 换行"),

        /** 单行超过 8192 字符。 */
        LINE_TOO_LONG("单行超过 8192 字符上限"),

        /** 行数超过 20000。 */
        TOO_MANY_LINES("清单行数超过 20000 上限"),

        /** 首行（去掉首尾空格/制表符后）不是 {@code #EXTM3U}。 */
        HEADER_MISSING("清单首行不是 #EXTM3U"),

        /** 首行之后再次出现 {@code #EXTM3U}。 */
        DUPLICATE_HEADER("清单出现重复的 #EXTM3U"),

        /** {@code #EXTINF} 缺少必需的逗号，或时长文本为空。 */
        EXTINF_MALFORMED("EXTINF 缺少逗号或时长文本为空"),

        /** 时长不是规范正十进制秒数（负数、零、科学计数、小数位超限、长度超限、含其它字符）。 */
        EXTINF_DURATION_INVALID("EXTINF 时长不是规范正十进制秒数"),

        /** 一个 {@code #EXTINF} 之后没有紧随一个分片地址行（含连续两个 {@code #EXTINF}）。 */
        EXTINF_WITHOUT_SEGMENT("EXTINF 之后没有紧随一个分片地址行"),

        /** 出现不属于任何 {@code #EXTINF} 的分片地址行。 */
        SEGMENT_URI_ORPHAN("出现不属于任何 EXTINF 的分片地址行"),

        /** 分片数超过 10000。 */
        SEGMENT_COUNT_EXCEEDED("分片数超过 10000 上限"),

        /** 缺少终止标记 {@code #EXT-X-ENDLIST}（可能是直播或未完成的清单）。 */
        ENDLIST_MISSING("清单缺少终止标记 EXT-X-ENDLIST"),

        /** 出现第二个 {@code #EXT-X-ENDLIST}。 */
        DUPLICATE_ENDLIST("清单出现重复的 EXT-X-ENDLIST"),

        /** {@code #EXT-X-ENDLIST} 之后还有非空内容（空行允许）。 */
        CONTENT_AFTER_ENDLIST("EXT-X-ENDLIST 之后还有非空内容"),

        /** 清单是 master 清单（{@code EXT-X-STREAM-INF} / {@code EXT-X-I-FRAME-STREAM-INF} / {@code EXT-X-MEDIA}）。 */
        MASTER_PLAYLIST_UNSUPPORTED("这是 master 清单，本解析器不递归选择媒体清单"),

        /** 清单含低延迟标签或未知的 {@code #EXT-X-} 标签（fail-closed）。 */
        UNSUPPORTED_PLAYLIST("清单包含本解析器不支持的 HLS 标签"),

        /** 清单含 {@code #EXT-X-GAP}（不可用分片不能计入候选时长）。 */
        GAP_SEGMENT_UNSUPPORTED("清单包含 EXT-X-GAP 不可用分片"),

        /** {@code #EXT-X-PLAYLIST-TYPE} 不是 {@code VOD}（{@code EVENT} 或其它值一律拒绝）。 */
        PLAYLIST_TYPE_NOT_VOD("清单 EXT-X-PLAYLIST-TYPE 不是 VOD"),

        /** 结构完整但没有任何完整分片。 */
        NO_COMPLETE_SEGMENT("清单没有任何完整分片"),

        /** 精确求和的时长无法表示为有限且大于零的 {@code double}。 */
        DURATION_NOT_FINITE("候选时长无法表示为有限的正秒数");

        private final String description;

        FailureCode(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }
}
