package com.feibijiubi.backend.videosource.integration.media;

import com.feibijiubi.backend.videosource.integration.media.HlsManifestParseException.FailureCode;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * M1：HLS <b>媒体清单</b>的纯离线候选时长解析器。
 *
 * <h2>边界（这一段就是本类的验收范围）</h2>
 * <ul>
 *   <li><b>不联网</b>：只收一个已经读进内存的 {@code byte[]}。没有网络客户端、没有重定向、没有重试，
 *       也不取分片、不取 {@code #EXT-X-KEY} 的密钥、不解密、不调用任何外部探测工具。分片地址行只按
 *       "这里有一行"计数，<b>不解析</b>、<b>不校验</b>、<b>不保存</b>、<b>不回显</b>。</li>
 *   <li><b>不是 Spring bean、不读配置、不打日志、不碰数据库、没有业务调用方</b>。它是一个
 *       {@code static} 纯函数，没有实例字段，因此没有可注入的 IO 能力；上游请求、管理员确认与审计都在
 *       后续阶段（M2/M4）另行实现。</li>
 *   <li><b>只校验计时结构，不证明播放有效</b>：解析通过只说明"这份字节符合本类声明的结构契约，并给出了
 *       分片时长之和"。它<b>不</b>证明清单自报的时长正确、<b>不</b>证明分片可访问、<b>不</b>证明
 *       {@code #EXT-X-KEY}/{@code #EXT-X-MAP} 的密钥或初始化段有效、<b>不</b>证明编解码器或地址可用，
 *       也<b>不</b>证明 {@code #EXT-X-ENDLIST} 之后上游不会替换内容。返回的
 *       {@link HlsMediaDurationCandidate} 只是候选值，管理员确认不能省略。</li>
 * </ul>
 *
 * <h2>接受的清单结构</h2>
 * <ul>
 *   <li>首行（去掉首尾空格/制表符后）必须恰好是 {@code #EXTM3U}；不接受 UTF-8 BOM，不接受首行空行。</li>
 *   <li>必须有终止标记 {@code #EXT-X-ENDLIST}（恰好一次），且其之后只允许空行。缺少终止标记按"直播或
 *       未完成清单"拒绝，不给出可确认数值。</li>
 *   <li>至少一个完整分片：一个 {@code #EXTINF:<时长>,<标题>} 之后必须紧随一个非 {@code #} 开头的分片地址行，
 *       中间可以插入注释与受支持的标签。连续两个 {@code #EXTINF}、{@code #EXTINF} 后直接
 *       {@code #EXT-X-ENDLIST} 或文件结束、以及不属于任何 {@code #EXTINF} 的地址行，全部拒绝。</li>
 *   <li>{@code #EXTINF} 的时长必须是规范正十进制（可带小数点，最多
 *       {@value #MAX_DURATION_FRACTION_DIGITS} 位小数），不接受负数、零、科学计数、{@code 1.}、
 *       {@code .5}、前后空白。逗号是必需的；逗号之后的标题可以是任意文本，但<b>只</b>被忽略，不进入结果、
 *       不进入异常文案。</li>
 *   <li>换行只支持 {@code LF} 与 {@code CRLF}；孤立的 {@code CR} 明确拒绝，不做"顺手当换行"的兼容。
 *       制表符允许出现在行内；NUL、其它 C0 控制字符与 C1 控制字符一律拒绝。</li>
 *   <li>空行（含只由空格/制表符组成的行）在任何位置都允许，包括 {@code #EXT-X-ENDLIST} 之后。结构行与
 *       标签行按去掉首尾空格/制表符后的文本判断，<b>地址行不做任何改写</b>（不 trim、不拼接、不归一化）。</li>
 * </ul>
 *
 * <h2>标签策略（fail-closed）</h2>
 * <p>明确支持的 {@code #EXT-X-} 标签白名单：{@code VERSION}、{@code TARGETDURATION}、{@code MEDIA-SEQUENCE}、
 * {@code DISCONTINUITY-SEQUENCE}、{@code PLAYLIST-TYPE}（只接受 {@code VOD}，{@code EVENT} 或其它值拒绝）、
 * {@code INDEPENDENT-SEGMENTS}、{@code DISCONTINUITY}、{@code KEY}、{@code MAP}、{@code BYTERANGE}、
 * {@code PROGRAM-DATE-TIME}、{@code START}。这些元数据<b>不深入校验</b>、不影响时长：本类不宣称验证了
 * 密钥、编解码器或初始化段地址的有效性，只把它们当作不改变计时结构的标记。</p>
 * <ul>
 *   <li>master 清单标签 {@code EXT-X-STREAM-INF} / {@code EXT-X-I-FRAME-STREAM-INF} / {@code EXT-X-MEDIA}
 *       一律拒绝为 {@link HlsManifestParseException.FailureCode#MASTER_PLAYLIST_UNSUPPORTED}：
 *       <b>不</b>自动挑选或递归抓取其中一个媒体清单，选择由管理员显式完成（M3）。</li>
 *   <li>低延迟标签 {@code EXT-X-PART} / {@code EXT-X-PRELOAD-HINT} / {@code EXT-X-SKIP}，以及任何不在白名单里的
 *       {@code #EXT-X-} 标签，都拒绝为 {@link HlsManifestParseException.FailureCode#UNSUPPORTED_PLAYLIST}。
 *       这比"忽略未知标签"更严格：宁可明确失败，也不让未知标签悄悄改变计时语义。</li>
 *   <li>{@code EXT-X-GAP} 拒绝：不可用分片不能计入可确认的候选时长。</li>
 *   <li>其它以 {@code #} 开头的行（普通注释、非 {@code #EXT-X-} 的标签）被忽略，不回显。</li>
 * </ul>
 *
 * <h2>硬上限（超限即失败，不截断、不 clamp）</h2>
 * <ul>
 *   <li>字节 {@value #MAX_MANIFEST_BYTES}（512 KiB，<b>先</b>判字节上限，<b>再</b>解码）；行数
 *       {@value #MAX_LINE_COUNT}；单行 {@value #MAX_LINE_LENGTH} 字符；完整分片数
 *       {@value #MAX_SEGMENT_COUNT}；单个时长文本 {@value #MAX_DURATION_TOKEN_LENGTH} 字符、小数最多
 *       {@value #MAX_DURATION_FRACTION_DIGITS} 位。</li>
 *   <li>这些数字是<b>我们自己的工程限额</b>，不是 HLS 协议的限制；超过限制的清单明确拒绝。</li>
 *   <li>逐行遍历，不使用 {@code split} 制造无界数量的行对象：每行最多切出一个不超过
 *       {@value #MAX_LINE_LENGTH} 字符的字符串，行数与字节数都已被硬限约束，因此内存与时间有界。</li>
 *   <li>行数预算允许完整的 10000 分片清单及额外元数据；行数与分片数分别校验，超限均明确拒绝。</li>
 * </ul>
 *
 * <h2>时长求值与 final 转换</h2>
 * <p>时长用 {@link BigDecimal} <b>精确</b>求和（{@code 0.1 + 0.2} 得到 {@code 0.3}，不是二进制浮点的
 * {@code 0.30000000000000004}）。返回前再转成 {@code double}，并要求它 {@code isFinite()} 且大于零，
 * 否则明确失败而不是返回 {@code Infinity}/{@code NaN}/{@code 0}。按上面的上限，单个时长文本最大约
 * {@code 10^22}、分片最多 {@value #MAX_SEGMENT_COUNT} 个，求和结果远小于 {@code double} 的有限范围，
 * 所以这个转换保护在当前上限下<b>不可达</b>；它只是防止将来放宽上限时静默产出非有限值。</p>
 *
 * <p>指纹 {@code SHA-256} 直接对传入的<b>原始字节</b>（含换行、含 BOM 之外的一切字节）计算，只作为
 * "解析过哪一份字节"的版本证据，不是签名，也不是可信认证。</p>
 */
public final class HlsMediaDurationParser {

    /** 清单最大字节数（512 KiB）。超出即失败，不解码、不截断。 */
    public static final int MAX_MANIFEST_BYTES = 512 * 1024;

    /** 最大行数（含首行与终止标记）。 */
    public static final int MAX_LINE_COUNT = 30000;

    /** 单行最大字符数（不含换行符）。 */
    public static final int MAX_LINE_LENGTH = 8192;

    /** 最大完整分片数，独立于行数上限检查。 */
    public static final int MAX_SEGMENT_COUNT = 10000;

    /** 单个 {@code #EXTINF} 时长文本最大字符数（工程限额）。 */
    public static final int MAX_DURATION_TOKEN_LENGTH = 32;

    /** 时长文本最多允许的小数位数（工程限额）。 */
    public static final int MAX_DURATION_FRACTION_DIGITS = 9;

    private static final String HEADER_TAG = "#EXTM3U";
    private static final String EXTINF_PREFIX = "#EXTINF:";
    private static final String EXT_X_PREFIX = "#EXT-X-";
    private static final String ENDLIST_TAG = "#EXT-X-ENDLIST";
    private static final String PLAYLIST_TYPE_TAG = "PLAYLIST-TYPE";
    private static final String PLAYLIST_TYPE_VOD = "VOD";

    /** 允许作为不透明元数据出现的 {@code #EXT-X-} 标签：只记录结构，不解释取值。 */
    private static final Set<String> OPAQUE_TAGS = Set.of(
            "VERSION",
            "TARGETDURATION",
            "MEDIA-SEQUENCE",
            "DISCONTINUITY-SEQUENCE",
            "INDEPENDENT-SEGMENTS",
            "DISCONTINUITY",
            "KEY",
            "MAP",
            "BYTERANGE",
            "PROGRAM-DATE-TIME",
            "START");

    /** master 清单标签：拒绝，绝不自动递归选择媒体清单。 */
    private static final Set<String> MASTER_TAGS = Set.of(
            "STREAM-INF",
            "I-FRAME-STREAM-INF",
            "MEDIA");

    /** 低延迟 HLS 标签：本解析器不支持。 */
    private static final Set<String> LOW_LATENCY_TAGS = Set.of(
            "PART",
            "PRELOAD-HINT",
            "SKIP");

    private static final String GAP_TAG = "GAP";

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private HlsMediaDurationParser() {
    }

    /**
     * 解析一份 HLS 媒体清单，返回候选时长、完整分片数与原始字节指纹。
     *
     * <p>失败一律抛 {@link HlsManifestParseException}（固定原因码、不带 cause、不回显清单内容）。
     * 方法没有任何 IO、没有可变状态，可以并发调用。</p>
     *
     * @param manifestBytes 清单原始字节；{@code null} 或空视为失败
     * @return 不可变候选值；<b>不是</b>已确认时长，也<b>不</b>表示可播放
     */
    public static HlsMediaDurationCandidate parse(byte[] manifestBytes) {
        if (manifestBytes == null || manifestBytes.length == 0) {
            throw fail(FailureCode.MANIFEST_EMPTY);
        }
        if (manifestBytes.length > MAX_MANIFEST_BYTES) {
            throw fail(FailureCode.MANIFEST_TOO_LARGE);
        }
        String text = decodeUtf8(manifestBytes);
        if (text.charAt(0) == '\uFEFF') {
            throw fail(FailureCode.BOM_UNSUPPORTED);
        }

        Accumulator accumulator = new Accumulator();
        int lineStart = 0;
        int lineCount = 0;
        while (lineStart < text.length()) {
            int cursor = lineStart;
            while (cursor < text.length()) {
                char current = text.charAt(cursor);
                if (current == '\n') {
                    break;
                }
                if (current == '\r') {
                    if (cursor + 1 >= text.length() || text.charAt(cursor + 1) != '\n') {
                        throw fail(FailureCode.LINE_ENDING_INVALID);
                    }
                    break;
                }
                if (cursor - lineStart >= MAX_LINE_LENGTH) {
                    throw fail(FailureCode.LINE_TOO_LONG);
                }
                if (isForbiddenControl(current)) {
                    throw fail(FailureCode.CONTROL_CHARACTER_UNSUPPORTED);
                }
                cursor++;
            }
            lineCount++;
            if (lineCount > MAX_LINE_COUNT) {
                throw fail(FailureCode.TOO_MANY_LINES);
            }
            String trimmed = stripSpacesAndTabs(text.substring(lineStart, cursor));
            if (lineCount == 1) {
                if (!HEADER_TAG.equals(trimmed)) {
                    throw fail(FailureCode.HEADER_MISSING);
                }
            } else if (trimmed.isEmpty()) {
                // 空行在任意位置都允许，包括 #EXT-X-ENDLIST 之后。
            } else if (accumulator.endListSeen) {
                throw fail(ENDLIST_TAG.equals(trimmed)
                        ? FailureCode.DUPLICATE_ENDLIST
                        : FailureCode.CONTENT_AFTER_ENDLIST);
            } else if (trimmed.charAt(0) == '#') {
                consumeTagLine(trimmed, accumulator);
            } else {
                consumeSegmentLine(accumulator);
            }
            lineStart = nextLineStart(text, cursor);
        }

        if (accumulator.pendingDuration != null) {
            throw fail(FailureCode.EXTINF_WITHOUT_SEGMENT);
        }
        if (!accumulator.endListSeen) {
            throw fail(FailureCode.ENDLIST_MISSING);
        }
        if (accumulator.segmentCount == 0) {
            throw fail(FailureCode.NO_COMPLETE_SEGMENT);
        }
        double seconds = accumulator.durationSeconds.doubleValue();
        if (!Double.isFinite(seconds) || seconds <= 0.0d) {
            throw fail(FailureCode.DURATION_NOT_FINITE);
        }
        return new HlsMediaDurationCandidate(
                accumulator.durationSeconds, accumulator.segmentCount, sha256Hex(manifestBytes));
    }

    /**
     * 处理一个标签行或注释行（已去掉首尾空格/制表符，且不是首行、不在 {@code ENDLIST} 之后）。
     */
    private static void consumeTagLine(String trimmed, Accumulator accumulator) {
        if (HEADER_TAG.equals(trimmed)) {
            throw fail(FailureCode.DUPLICATE_HEADER);
        }
        if (trimmed.startsWith(EXTINF_PREFIX)) {
            consumeExtInf(trimmed, accumulator);
            return;
        }
        if ("#EXTINF".equals(trimmed)) {
            throw fail(FailureCode.EXTINF_MALFORMED);
        }
        if (!trimmed.startsWith(EXT_X_PREFIX)) {
            // 普通注释或非 EXT-X- 的标签：忽略，不回显。
            return;
        }
        String name = extXTagName(trimmed);
        if ("ENDLIST".equals(name)) {
            if (!ENDLIST_TAG.equals(trimmed)) {
                throw fail(FailureCode.UNSUPPORTED_PLAYLIST);
            }
            if (accumulator.pendingDuration != null) {
                throw fail(FailureCode.EXTINF_WITHOUT_SEGMENT);
            }
            accumulator.endListSeen = true;
            return;
        }
        if (PLAYLIST_TYPE_TAG.equals(name)) {
            if (!PLAYLIST_TYPE_VOD.equals(extXTagValue(trimmed))) {
                throw fail(FailureCode.PLAYLIST_TYPE_NOT_VOD);
            }
            return;
        }
        if (MASTER_TAGS.contains(name)) {
            throw fail(FailureCode.MASTER_PLAYLIST_UNSUPPORTED);
        }
        if (LOW_LATENCY_TAGS.contains(name)) {
            throw fail(FailureCode.UNSUPPORTED_PLAYLIST);
        }
        if (GAP_TAG.equals(name)) {
            throw fail(FailureCode.GAP_SEGMENT_UNSUPPORTED);
        }
        if (!OPAQUE_TAGS.contains(name)) {
            throw fail(FailureCode.UNSUPPORTED_PLAYLIST);
        }
    }

    /** 处理一个 {@code #EXTINF:} 行：只取逗号前的时长文本，标题一律忽略且不回显。 */
    private static void consumeExtInf(String trimmed, Accumulator accumulator) {
        if (accumulator.pendingDuration != null) {
            throw fail(FailureCode.EXTINF_WITHOUT_SEGMENT);
        }
        String body = trimmed.substring(EXTINF_PREFIX.length());
        int comma = body.indexOf(',');
        if (comma < 0) {
            throw fail(FailureCode.EXTINF_MALFORMED);
        }
        String token = body.substring(0, comma);
        if (token.isEmpty()) {
            throw fail(FailureCode.EXTINF_MALFORMED);
        }
        accumulator.pendingDuration = parseDurationToken(token);
    }

    /** 处理一个分片地址行：只确认"这里有一行地址"，地址文本本身不解析、不保存。 */
    private static void consumeSegmentLine(Accumulator accumulator) {
        if (accumulator.pendingDuration == null) {
            throw fail(FailureCode.SEGMENT_URI_ORPHAN);
        }
        if (accumulator.segmentCount >= MAX_SEGMENT_COUNT) {
            throw fail(FailureCode.SEGMENT_COUNT_EXCEEDED);
        }
        accumulator.segmentCount++;
        accumulator.durationSeconds = accumulator.durationSeconds.add(accumulator.pendingDuration);
        accumulator.pendingDuration = null;
    }

    /**
     * 校验并转换一个规范正十进制时长文本。只接受 {@code 数字[.数字]}，长度与小数位都受上限约束。
     */
    private static BigDecimal parseDurationToken(String token) {
        int length = token.length();
        if (length == 0 || length > MAX_DURATION_TOKEN_LENGTH) {
            throw fail(FailureCode.EXTINF_DURATION_INVALID);
        }
        int integerDigits = 0;
        int fractionDigits = 0;
        boolean dotSeen = false;
        for (int i = 0; i < length; i++) {
            char current = token.charAt(i);
            if (current == '.') {
                if (dotSeen) {
                    throw fail(FailureCode.EXTINF_DURATION_INVALID);
                }
                dotSeen = true;
                continue;
            }
            if (current < '0' || current > '9') {
                throw fail(FailureCode.EXTINF_DURATION_INVALID);
            }
            if (dotSeen) {
                fractionDigits++;
            } else {
                integerDigits++;
            }
        }
        if (integerDigits == 0 || (dotSeen && fractionDigits == 0)) {
            throw fail(FailureCode.EXTINF_DURATION_INVALID);
        }
        if (fractionDigits > MAX_DURATION_FRACTION_DIGITS) {
            throw fail(FailureCode.EXTINF_DURATION_INVALID);
        }
        BigDecimal value = new BigDecimal(token);
        if (value.signum() <= 0) {
            throw fail(FailureCode.EXTINF_DURATION_INVALID);
        }
        return value;
    }

    /** 取 {@code #EXT-X-} 之后的标签名（到 {@code ':'} 或行尾为止）。 */
    private static String extXTagName(String trimmed) {
        int colon = trimmed.indexOf(':', EXT_X_PREFIX.length());
        return colon < 0
                ? trimmed.substring(EXT_X_PREFIX.length())
                : trimmed.substring(EXT_X_PREFIX.length(), colon);
    }

    /** 取 {@code #EXT-X-} 标签冒号之后的取值（去掉首尾空格/制表符）；没有冒号时为空串。 */
    private static String extXTagValue(String trimmed) {
        int colon = trimmed.indexOf(':', EXT_X_PREFIX.length());
        return colon < 0 ? "" : stripSpacesAndTabs(trimmed.substring(colon + 1));
    }

    /** 严格 UTF-8 解码：非法字节序列直接失败，不用替换字符降级。 */
    private static String decodeUtf8(byte[] manifestBytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(manifestBytes)).toString();
        } catch (CharacterCodingException failure) {
            // 不挂 cause：解码异常文案会带字节位置，这里只需要固定原因码。
            throw new HlsManifestParseException(FailureCode.MANIFEST_NOT_UTF8);
        }
    }

    /**
     * 是否是不允许的控制字符。换行与回车由行扫描单独处理，制表符允许出现在行内。
     */
    private static boolean isForbiddenControl(char current) {
        if (current == '\t') {
            return false;
        }
        if (current < 0x20) {
            return true;
        }
        if (current == 0x7F) {
            return true;
        }
        return current >= 0x80 && current <= 0x9F;
    }

    /** 去掉行首行尾的空格与制表符；只用于结构/标签判断，地址行不做改写。 */
    private static String stripSpacesAndTabs(String line) {
        int start = 0;
        int end = line.length();
        while (start < end && (line.charAt(start) == ' ' || line.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        return line.substring(start, end);
    }

    /** 计算下一行的起始下标；{@code terminatorIndex} 指向换行符或文本末尾。 */
    private static int nextLineStart(String text, int terminatorIndex) {
        if (terminatorIndex >= text.length()) {
            return text.length();
        }
        return text.charAt(terminatorIndex) == '\r' ? terminatorIndex + 2 : terminatorIndex + 1;
    }

    /** 对原始字节（含换行）计算 SHA-256，返回 64 位小写十六进制。 */
    private static String sha256Hex(byte[] manifestBytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JVM 不支持 SHA-256", failure);
        }
        byte[] hash = digest.digest(manifestBytes);
        char[] hex = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int value = hash[i] & 0xFF;
            hex[i * 2] = HEX_DIGITS[value >>> 4];
            hex[i * 2 + 1] = HEX_DIGITS[value & 0x0F];
        }
        return new String(hex);
    }

    private static HlsManifestParseException fail(HlsManifestParseException.FailureCode code) {
        return new HlsManifestParseException(code);
    }

    /** 逐行扫描期间的可变累加状态；只在一次 {@link #parse(byte[])} 调用内存在。 */
    private static final class Accumulator {

        private BigDecimal durationSeconds = BigDecimal.ZERO;
        private BigDecimal pendingDuration;
        private int segmentCount;
        private boolean endListSeen;
    }
}
