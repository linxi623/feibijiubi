package com.feibijiubi.backend.integration.videosource.applecms;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.feibijiubi.backend.integration.videosource.ExternalVideoUrlPolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * E2-B：苹果CMS（maccms 系）{@code api.php/provide/vod/} JSON 响应的<b>纯离线</b>解析器。
 *
 * <h2>边界（这一段就是本类的验收范围）</h2>
 * <ul>
 *   <li><b>不联网</b>：只收一个已经读进内存的 {@code byte[]}。没有 HTTP 客户端、没有重定向、没有重试，
 *       也没有"顺手建个连接测试"的入口。响应体大小上限由调用方（将来的受限 HTTP 客户端）与这里的
 *       {@value #MAX_BODY_BYTES} 字节双重约束。</li>
 *   <li><b>不是 Spring bean、不读配置、不打日志、不碰数据库、没有业务调用方</b>。它没有
 *       {@code @Component}/{@code @Service}，也不会被组件扫描注入到任何现有链路里；E4 之前它只是一段
 *       被单元测试调用的纯函数。</li>
 *   <li><b>解析 ≠ 可用</b>：解析通过只保证"这份字节符合本适配器声明的结构"，<b>不</b>保证地址可播放、
 *       不保证格式是 MP4/HLS、<b>不</b>保证有授权、<b>不</b>保证时长已知、<b>不</b>保证集数正确。
 *       线路键里的 {@code gsyun}、{@code gsm3u8} 与地址后缀都不是格式证据（见 {@link AppleCmsPlaybackState}）。</li>
 *   <li><b>不执行任何上游内容</b>：不解析 HTML、不执行脚本、不跟随线路里的外链、不读 {@code vod_content}
 *       之类的简介/演员字段。未知字段一律跳过（但仍计入总大小与嵌套深度上限）。</li>
 * </ul>
 *
 * <h2>两个入口</h2>
 * <ul>
 *   <li>{@link #parseList(byte[])}：{@code ?ac=list} 一页 → {@link AppleCmsListSnapshot}（目录概要 + 分类 +
 *       分页）。列表可能根本没有播放地址，所以它<b>只</b>输出目录信息，不把列表当详情。</li>
 *   <li>{@link #parseDetail(byte[])}：详情请求（{@code ac=detail} 加一个作品 ID）恰好一条作品 →
 *       {@link AppleCmsDetailSnapshot}（线路 + 分集条目）。</li>
 * </ul>
 * <p>两个入口都要求 {@code code == 1}；其它值、缺失或非整数一律明确失败，不存在"降级成空列表"的分支。</p>
 *
 * <h2>分隔符的字面语义</h2>
 * <ul>
 *   <li>{@code vod_play_from} / {@code vod_play_url} 按字面 {@code $$$} 切成线路，<b>保留空段</b>
 *       （{@code split(..., -1)}），所以"多一个 {@code $$$}"会导致段数不一致或空线路键，而不是被静默吞掉。</li>
 *   <li>一条线路内按 {@code #} 切分集条目，同样保留空段：尾部 {@code #} 会产生空段并失败。</li>
 *   <li>一个条目用<b>第一个</b> {@code $} 分成"标签 | 地址"，剩余部分原样作为地址；因此地址 query 里出现
 *       {@code $} 不会被改写（{@code token=g$amma} 会完整保留）。</li>
 *   <li>{@code #} 是分集分隔符，<b>不是</b> URI fragment：地址里写 {@code #fragment} 会被切成两条，从而
 *       明确失败，不会被当成"带片段的可播放地址"。</li>
 *   <li>不按标签去重：不同线路出现同名集、同一线路重复同名集，都保留为独立条目，绝不互相覆盖。</li>
 * </ul>
 *
 * <h2>地址校验</h2>
 * <p>每个地址都交给构造时注入的 {@link ExternalVideoUrlPolicy} 校验（HTTPS + 精确 host:port 允许列表 +
 * 无 userinfo/片段/控制字符）。允许列表<b>只能来自部署配置</b>：它由构造参数传入，绝不由响应内容扩充，
 * 也不会因为某个地址"看起来在上游页面里出现过"而放行。策略拒绝时本类抛固定原因码
 * {@link AppleCmsParseException.FailureCode#EPISODE_ADDRESS_INVALID}，不带原始地址、不带 cause。</p>
 *
 * <h2>硬上限（超限即失败，不截断、不 clamp）</h2>
 * <ul>
 *   <li>响应体 {@value #MAX_BODY_BYTES} 字节；JSON 嵌套深度 {@value #MAX_JSON_DEPTH}；
 *       单个数值字面量 200 位（超长触发 {@code RESOURCE_LIMIT_EXCEEDED}）。</li>
 *   <li>单页条目 {@value #MAX_LIST_ITEMS}；上游 {@code class} 分类列表 {@value #MAX_CLASS_ENTRIES} 条；
 *       线路 {@value #MAX_PLAY_LINES}；单线路条目 {@value #MAX_EPISODES_PER_LINE}；单地址
 *       {@value #MAX_PLAY_ADDRESS_LENGTH} 字符。</li>
 *   <li>分隔符是<b>先计数、后切分</b>：{@code $$$} / {@code #} 的出现次数在任何数组分配之前用一次线性
 *       扫描统计，超过线路/分集上限就直接失败。因此一段 512 KiB 的分隔符轰炸体不会被切成几十万段，
 *       上限是分配上限，不只是事后检查。</li>
 *   <li>作品身份 {@value #MAX_WORK_IDENTITY_LENGTH} 字符、线路键 {@value #MAX_LINE_KEY_LENGTH} 字符、
 *       标题 {@value #MAX_TITLE_LENGTH}、分类文本 {@value #MAX_CATEGORY_TEXT_LENGTH}、
 *       分集标签 {@value #MAX_EPISODE_LABEL_LENGTH}、时长提示 {@value #MAX_DURATION_HINT_LENGTH}。</li>
 * </ul>
 * <p>这些数字是我们自己的列宽/响应工程上限，不是 CMS 协议的规定。上游 {@code limit} 只被当作元数据记录，
 * <b>不会</b>用来预分配集合：本类最多分配 {@value #MAX_LIST_ITEMS} 条目录条目、{@value #MAX_PLAY_LINES}
 * 条线路，以及每条线路 {@value #MAX_EPISODES_PER_LINE} 条分集条目——分集也遵守这个上限，因为分隔符
 * 数量是在切分之前统计的。</p>
 *
 * <h2>JSON 严格性</h2>
 * <p>使用本类自己的 {@link JsonFactory}（不改全局 Jackson 配置、不注入 Spring 的 {@code ObjectMapper}）：
 * 严格重复字段名（根对象、参与解释的对象，以及被跳过的未知子树都检查）、拒绝根值之后的多余内容、
 * 显式区分 {@code VALUE_STRING} / {@code VALUE_NUMBER_INT} / {@code VALUE_NUMBER_FLOAT}。
 * <b>不使用</b> {@code asInt()} 之类的宽松转换器：数字字段只有"规范十进制"一条路，{@code 1.0}、
 * {@code " 1"}、{@code "1e3"}、{@code "01"}、{@code -1} 全部明确失败，不会静默变成 {@code 0}。</p>
 *
 * <p>重复字段名由本类在逐字段遍历时用 {@code HashSet} 判定，而不是打开 Jackson 的
 * {@code STRICT_DUPLICATE_DETECTION}：Jackson 2.x 没有专门的重复键异常类型，会把它报成普通
 * {@link JsonProcessingException}，那样就只能靠匹配英文 message 才能区分原因码。</p>
 *
 * <p>字符串长度上限分两层：<b>参与解释</b>的字段逐个按自己的上限拒绝（见上）；被跳过的未知字段
 * （例如 {@code vod_content} 简介）不设单独的字符串长度上限，只受 {@value #MAX_BODY_BYTES} 字节的
 * 整体上限约束——否则一条正常的长简介会让整份响应解析失败。数字字面量则另有 200 位硬上限，
 * 避免一个超长整数把解析拖进大数运算。</p>
 */
public final class AppleCmsJsonAdapter {

    /** 响应体最大字节数（512 KiB）。超出即失败，不解析、不截断。 */
    public static final int MAX_BODY_BYTES = 512 * 1024;

    /** JSON 嵌套最大深度（根对象记为第 1 层）。 */
    public static final int MAX_JSON_DEPTH = 32;

    /** 单页最多解析多少条目录条目；不受上游 {@code limit} 影响。 */
    public static final int MAX_LIST_ITEMS = 100;

    /** 标题快照最大长度（对齐 {@code title_snapshot VARCHAR(255)}）。 */
    public static final int MAX_TITLE_LENGTH = 255;

    /** 分类文本（{@code type_name}）最大长度。 */
    public static final int MAX_CATEGORY_TEXT_LENGTH = 256;

    /** 上游 {@code class} 分类列表最多多少条（只计数，不解释内容）。 */
    public static final int MAX_CLASS_ENTRIES = 256;

    /** 分类标识（{@code type_id}）最大字符数（对齐 {@code mc_id VARCHAR(20)}）。 */
    public static final int MAX_CATEGORY_ID_LENGTH = 20;

    /** 作品身份最大字符数（对齐 {@code upstream_series_id VARCHAR(191)}）。 */
    public static final int MAX_WORK_IDENTITY_LENGTH = 191;

    /** 线路键最大字符数（对齐 {@code line_key VARCHAR(191)}）。 */
    public static final int MAX_LINE_KEY_LENGTH = 191;

    /** 分集标签最大长度（展示用快照，同标题口径）。 */
    public static final int MAX_EPISODE_LABEL_LENGTH = 255;

    /** 一条作品最多解析多少条线路。 */
    public static final int MAX_PLAY_LINES = 16;

    /** 一条线路最多解析多少条分集条目。 */
    public static final int MAX_EPISODES_PER_LINE = 1000;

    /** 单个播放地址最大字符数（契约里对外的单条 URL 上限）。 */
    public static final int MAX_PLAY_ADDRESS_LENGTH = 2048;

    /** 作品级时长提示文本最大长度（只是提示，永不换算成秒）。 */
    public static final int MAX_DURATION_HINT_LENGTH = 64;

    /**
     * 单个数值字面量的最大长度。比作品身份的 191 位略宽，这样一个 191 位的整数型 {@code vod_id}
     * 仍然由身份规则（而不是解析器约束）拒绝，原因码更准确。
     */
    private static final int MAX_NUMBER_LITERAL_LENGTH = 200;

    /** 线路分隔符的字面值；计数用它，切分也用它（正则由 {@link Pattern#quote(String)} 从同一个字面值派生）。 */
    private static final String PLAY_LINE_SEPARATOR_LITERAL = "$$$";

    /** 线路分隔符：字面 {@code $$$} 对应的正则（{@code $} 在正则里是锚点，所以用 {@link Pattern#quote(String)} 派生）。 */
    private static final Pattern PLAY_LINE_SEPARATOR = Pattern.compile(Pattern.quote(PLAY_LINE_SEPARATOR_LITERAL));

    /** 分集分隔符：字面 {@code #}；它是协议分隔符，不是 URI fragment。 */
    private static final String EPISODE_SEPARATOR = "#";

    /** 标签与地址的分隔符：只取<b>第一个</b>，剩余部分原样作为地址。 */
    private static final char LABEL_ADDRESS_SEPARATOR = '$';

    /** maccms 成功码。 */
    private static final String SUCCESS_CODE = "1";

    private final ExternalVideoUrlPolicy allowedUrlPolicy;
    private final JsonFactory jsonFactory;

    /**
     * @param allowedUrlPolicy 校验每个播放地址的语法策略；<b>必须由部署配置构造</b>（不允许为空，也不允许
     *                         从响应内容推导）。本类只读使用，不修改它
     */
    public AppleCmsJsonAdapter(ExternalVideoUrlPolicy allowedUrlPolicy) {
        if (allowedUrlPolicy == null) {
            throw new IllegalArgumentException("允许端点策略不能为空：没有策略就无法校验任何播放地址");
        }
        this.allowedUrlPolicy = allowedUrlPolicy;
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNumberLength(MAX_NUMBER_LITERAL_LENGTH)
                .build();
        // 本类自己的 factory：不改全局 Jackson 配置，也不共享 Spring 的 ObjectMapper。
        this.jsonFactory = new JsonFactoryBuilder()
                .streamReadConstraints(constraints)
                .build();
    }

    /**
     * 解析列表请求（{@code ac=list}）的一页。
     *
     * <p>本响应必须同时带上 {@code list} 与 {@code page}/{@code pagecount}/{@code limit}/{@code total}
     * 四个分页字段：空页也是“{@code list: []} + total=0 + pagecount=0 + page=1”，而不是丢掉这些字段。
     * 缺失的分页字段会以 {@code PAGINATION_MISSING} 明确失败，不会默认为 0 或 1。</p>
     *
     * <p><b>整页 all-or-nothing</b>：只要本页任意一条作品不完整（例如缺 {@code vod_id} 或 {@code vod_name}），
     * 整页就失败，<b>不会</b>返回“跳过坏条目后的部分结果”，也不返回任何翻页检查点。逐条跳过或整页重试
     * 是调用方（E4 同步任务）的选择，必须显式决定，不能由解析器静默替它做。</p>
     *
     * @param body 已经读完的响应体（UTF-8 JSON），最多 {@value #MAX_BODY_BYTES} 字节
     * @return 只读目录快照
     * @throws AppleCmsParseException 任何结构、类型、上限或地址校验失败；固定原因码，不含响应内容
     */
    public AppleCmsListSnapshot parseList(byte[] body) {
        byte[] bounded = requireBody(body);
        try (JsonParser parser = jsonFactory.createParser(bounded)) {
            requireToken(parser.nextToken(), JsonToken.START_OBJECT,
                    AppleCmsParseException.FailureCode.ROOT_NOT_OBJECT);
            AppleCmsListSnapshot snapshot = readListRoot(parser, 1);
            requireNoTrailingContent(parser);
            return snapshot;
        } catch (StreamConstraintsException e) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.RESOURCE_LIMIT_EXCEEDED);
        } catch (JsonProcessingException e) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.MALFORMED_JSON);
        } catch (IOException e) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.MALFORMED_JSON);
        }
    }

    /**
     * 解析详情响应（{@code ac=detail} 加一个作品 ID）里的一条作品。
     *
     * <p>{@code list} 必须<b>恰好</b>包含一条作品：0 条或多条都明确失败，避免"取第一条"这种静默选择。</p>
     *
     * <p><b>本方法无法核对“这条作品就是请求的那条”</b>：它只拿到响应体，拿不到请求参数。上游返回了另一条
     * 作品时这里不会失败，所以<b>调用方必须自己比对</b> {@link AppleCmsDetailSnapshot#workIdentity()} 与
     * 请求的作品 ID，不一致就不绑定（E4 的责任，不是解析器的）。</p>
     *
     * @param body 已经读完的响应体（UTF-8 JSON），最多 {@value #MAX_BODY_BYTES} 字节
     * @return 只读详情快照；{@link AppleCmsDetailSnapshot#workIdentity()} 是否等于请求的 ID 由调用方比对
     * @throws AppleCmsParseException 任何结构、类型、上限或地址校验失败；固定原因码，不含响应内容
     */
    public AppleCmsDetailSnapshot parseDetail(byte[] body) {
        byte[] bounded = requireBody(body);
        try (JsonParser parser = jsonFactory.createParser(bounded)) {
            requireToken(parser.nextToken(), JsonToken.START_OBJECT,
                    AppleCmsParseException.FailureCode.ROOT_NOT_OBJECT);
            AppleCmsDetailSnapshot snapshot = readDetailRoot(parser, 1);
            requireNoTrailingContent(parser);
            return snapshot;
        } catch (StreamConstraintsException e) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.RESOURCE_LIMIT_EXCEEDED);
        } catch (JsonProcessingException e) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.MALFORMED_JSON);
        } catch (IOException e) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.MALFORMED_JSON);
        }
    }

    // ------------------------------------------------------------------ 根对象

    private AppleCmsListSnapshot readListRoot(JsonParser parser, int depth) throws IOException {
        requireDepth(depth);
        Set<String> seenFields = new HashSet<>();
        List<AppleCmsCatalogEntry> items = null;
        Long page = null;
        Long pageCount = null;
        Long limit = null;
        Long total = null;
        boolean codeChecked = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireFieldName(parser.currentToken());
            String name = parser.currentName();
            requireNewField(seenFields, name);
            JsonToken valueToken = parser.nextToken();
            switch (name) {
                case "code" -> {
                    readSuccessCode(parser, valueToken);
                    codeChecked = true;
                }
                case "list" -> items = readCatalogItems(parser, valueToken, depth + 1);
                // class 是上游附带的分类列表：只限制条目数，不解释内容（条目分类看自己的 type_id）。
                case "class" -> readClassList(parser, valueToken, depth + 1);
                case "page" -> page = readPaginationValue(parser, valueToken, true);
                case "pagecount" -> pageCount = readPaginationValue(parser, valueToken, false);
                case "limit" -> limit = readPaginationValue(parser, valueToken, true);
                case "total" -> total = readPaginationValue(parser, valueToken, false);
                default -> skipValue(parser, valueToken, depth + 1);
            }
        }
        if (!codeChecked) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.UPSTREAM_CODE_NOT_SUCCESS);
        }
        if (items == null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.LIST_MISSING);
        }
        if (page == null || pageCount == null || limit == null || total == null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.PAGINATION_MISSING);
        }
        return new AppleCmsListSnapshot(items, new AppleCmsPagination(page, pageCount, limit, total));
    }

    private AppleCmsDetailSnapshot readDetailRoot(JsonParser parser, int depth) throws IOException {
        requireDepth(depth);
        Set<String> seenFields = new HashSet<>();
        AppleCmsDetailSnapshot detail = null;
        boolean codeChecked = false;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireFieldName(parser.currentToken());
            String name = parser.currentName();
            requireNewField(seenFields, name);
            JsonToken valueToken = parser.nextToken();
            switch (name) {
                case "code" -> {
                    readSuccessCode(parser, valueToken);
                    codeChecked = true;
                }
                case "list" -> detail = readDetailItem(parser, valueToken, depth + 1);
                // 详情响应里的 page/pagecount/limit/total 对单作品没有意义，按未知字段跳过。
                default -> skipValue(parser, valueToken, depth + 1);
            }
        }
        if (!codeChecked) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.UPSTREAM_CODE_NOT_SUCCESS);
        }
        if (detail == null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.DETAIL_ITEM_COUNT_INVALID);
        }
        return detail;
    }

    // ------------------------------------------------------------------ 列表

    private List<AppleCmsCatalogEntry> readCatalogItems(JsonParser parser, JsonToken valueToken, int depth)
            throws IOException {
        if (valueToken == JsonToken.VALUE_NULL) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.LIST_MISSING);
        }
        if (valueToken != JsonToken.START_ARRAY) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.LIST_NOT_ARRAY);
        }
        requireDepth(depth);
        List<AppleCmsCatalogEntry> items = new ArrayList<>();
        while (true) {
            JsonToken itemToken = parser.nextToken();
            if (itemToken == JsonToken.END_ARRAY) {
                break;
            }
            if (itemToken == null) {
                throw new AppleCmsParseException(AppleCmsParseException.FailureCode.MALFORMED_JSON);
            }
            if (itemToken != JsonToken.START_OBJECT) {
                throw new AppleCmsParseException(AppleCmsParseException.FailureCode.LIST_ITEM_NOT_OBJECT);
            }
            if (items.size() >= MAX_LIST_ITEMS) {
                throw new AppleCmsParseException(AppleCmsParseException.FailureCode.LIST_TOO_LONG);
            }
            items.add(readCatalogItem(parser, depth + 1));
        }
        return items;
    }

    /**
     * 上游 {@code class} 分类列表：只校验形状与条目数上限，<b>不</b>读取任何分类名。
     *
     * <p>本切片输出的是“作品身份 + 标题 + 分类标识/分类名 + 线路地址”这几样，其中每条作品的分类来自它
     * 自己的 {@code type_id}/{@code type_name}，所以这份全局列表在<b>当前输出里</b>没有消费方。但"不读"
     * 不等于"没有价值"：它是上游的分类字典，只有名字没有 ID 的纯名字型 CMS 退化路径、以及管理端展示
     * 上游分类字典，都需要它（E3 分类映射的范围）。这里只做了“形状 + 条数上限”的边界，既不输出字典，
     * 也不声称它永远重复无用。</p>
     *
     * <p>不读内容也不能不设限：否则它就成了绕过单页条目上限的旁路。</p>
     */
    private void readClassList(JsonParser parser, JsonToken valueToken, int depth) throws IOException {
        if (valueToken == JsonToken.VALUE_NULL) {
            return;
        }
        if (valueToken != JsonToken.START_ARRAY) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.CLASS_LIST_INVALID);
        }
        requireDepth(depth);
        int count = 0;
        while (true) {
            JsonToken itemToken = parser.nextToken();
            if (itemToken == JsonToken.END_ARRAY) {
                return;
            }
            count++;
            if (count > MAX_CLASS_ENTRIES) {
                throw new AppleCmsParseException(AppleCmsParseException.FailureCode.CLASS_LIST_TOO_LONG);
            }
            skipValue(parser, itemToken, depth + 1);
        }
    }

    private AppleCmsCatalogEntry readCatalogItem(JsonParser parser, int depth) throws IOException {
        requireDepth(depth);
        Set<String> seenFields = new HashSet<>();
        String workIdentity = null;
        String title = null;
        String categoryId = null;
        String categoryName = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireFieldName(parser.currentToken());
            String name = parser.currentName();
            requireNewField(seenFields, name);
            JsonToken valueToken = parser.nextToken();
            switch (name) {
                case "vod_id" -> workIdentity = readWorkIdentity(parser, valueToken);
                case "vod_name" -> title = readTitle(parser, valueToken);
                case "type_id" -> categoryId = readCategoryId(parser, valueToken);
                case "type_name" -> categoryName = readCategoryText(parser, valueToken);
                // 列表里的 vod_play_from / vod_play_url 刻意不读：列表不是详情。
                default -> skipValue(parser, valueToken, depth + 1);
            }
        }
        if (workIdentity == null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.WORK_IDENTITY_INVALID);
        }
        if (title == null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.TITLE_INVALID);
        }
        return new AppleCmsCatalogEntry(workIdentity, title, categoryId, categoryName);
    }

    // ------------------------------------------------------------------ 详情

    private AppleCmsDetailSnapshot readDetailItem(JsonParser parser, JsonToken valueToken, int depth)
            throws IOException {
        if (valueToken == JsonToken.VALUE_NULL) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.DETAIL_ITEM_COUNT_INVALID);
        }
        if (valueToken != JsonToken.START_ARRAY) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.LIST_NOT_ARRAY);
        }
        requireDepth(depth);
        AppleCmsDetailSnapshot detail = null;
        int itemCount = 0;
        while (true) {
            JsonToken itemToken = parser.nextToken();
            if (itemToken == JsonToken.END_ARRAY) {
                break;
            }
            if (itemToken == null) {
                throw new AppleCmsParseException(AppleCmsParseException.FailureCode.MALFORMED_JSON);
            }
            if (itemToken != JsonToken.START_OBJECT) {
                throw new AppleCmsParseException(AppleCmsParseException.FailureCode.LIST_ITEM_NOT_OBJECT);
            }
            itemCount++;
            if (itemCount > 1) {
                throw new AppleCmsParseException(AppleCmsParseException.FailureCode.DETAIL_ITEM_COUNT_INVALID);
            }
            detail = readDetailObject(parser, depth + 1);
        }
        if (detail == null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.DETAIL_ITEM_COUNT_INVALID);
        }
        return detail;
    }

    private AppleCmsDetailSnapshot readDetailObject(JsonParser parser, int depth) throws IOException {
        requireDepth(depth);
        Set<String> seenFields = new HashSet<>();
        String workIdentity = null;
        String title = null;
        String categoryId = null;
        String categoryName = null;
        String durationHint = null;
        String playFrom = null;
        String playUrl = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireFieldName(parser.currentToken());
            String name = parser.currentName();
            requireNewField(seenFields, name);
            JsonToken valueToken = parser.nextToken();
            switch (name) {
                case "vod_id" -> workIdentity = readWorkIdentity(parser, valueToken);
                case "vod_name" -> title = readTitle(parser, valueToken);
                case "type_id" -> categoryId = readCategoryId(parser, valueToken);
                case "type_name" -> categoryName = readCategoryText(parser, valueToken);
                case "vod_duration" -> durationHint = readDurationHint(parser, valueToken, depth + 1);
                case "vod_play_from" -> playFrom = readPlayLineText(parser, valueToken);
                case "vod_play_url" -> playUrl = readPlayLineText(parser, valueToken);
                // vod_total（"总集数"）刻意不读：探测样本里它与实际条目数矛盾，不能当集数用。
                default -> skipValue(parser, valueToken, depth + 1);
            }
        }
        if (workIdentity == null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.WORK_IDENTITY_INVALID);
        }
        if (title == null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.TITLE_INVALID);
        }
        if (playFrom == null || playUrl == null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.PLAY_LINES_MISSING);
        }
        return new AppleCmsDetailSnapshot(workIdentity, title, categoryId, categoryName, durationHint,
                readPlayLines(playFrom, playUrl));
    }

    private List<AppleCmsPlayLine> readPlayLines(String playFrom, String playUrl) {
        if (playFrom.isBlank() || playUrl.isBlank()) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.NO_PLAY_LINES);
        }
        // 先数分隔符再切分：段数 = 出现次数 + 1，与下面的 split(..., -1) 完全一致，所以上限判断发生在
        // 任何数组分配之前（一段 512 KiB 的 "$$$" 轰炸体不会先被切成几十万段）。顺序与原来一致：
        // 段数不一致先报 PLAY_LINE_COUNT_MISMATCH，再报 TOO_MANY_PLAY_LINES。
        int lineCount = countOccurrences(playFrom, PLAY_LINE_SEPARATOR_LITERAL) + 1;
        int urlLineCount = countOccurrences(playUrl, PLAY_LINE_SEPARATOR_LITERAL) + 1;
        if (lineCount != urlLineCount) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.PLAY_LINE_COUNT_MISMATCH);
        }
        if (lineCount > MAX_PLAY_LINES) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.TOO_MANY_PLAY_LINES);
        }
        String[] fromParts = PLAY_LINE_SEPARATOR.split(playFrom, -1);
        String[] urlParts = PLAY_LINE_SEPARATOR.split(playUrl, -1);
        Set<String> lineKeys = new HashSet<>();
        List<AppleCmsPlayLine> lines = new ArrayList<>(fromParts.length);
        for (int i = 0; i < fromParts.length; i++) {
            String lineKey = requireLineKey(fromParts[i]);
            if (!lineKeys.add(lineKey)) {
                throw new AppleCmsParseException(AppleCmsParseException.FailureCode.DUPLICATE_LINE_KEY);
            }
            lines.add(new AppleCmsPlayLine(i + 1, lineKey, readEpisodeEntries(urlParts[i])));
        }
        return List.copyOf(lines);
    }

    private String requireLineKey(String rawLineKey) {
        if (rawLineKey.isBlank() || rawLineKey.length() > MAX_LINE_KEY_LENGTH) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.LINE_KEY_INVALID);
        }
        // 不裁剪：首尾空白会制造"看起来相同、实际不同"的线路身份，直接拒绝比悄悄改值安全。
        if (!rawLineKey.equals(rawLineKey.strip())) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.LINE_KEY_INVALID);
        }
        return rawLineKey;
    }

    private List<AppleCmsLineEntry> readEpisodeEntries(String rawUrlText) {
        if (rawUrlText.isBlank()) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.EMPTY_LINE_GROUP);
        }
        // 同样先数后切：段数 = '#' 出现次数 + 1，超过上限立刻失败，不先分配全部分段。
        if (countOccurrences(rawUrlText, EPISODE_SEPARATOR) + 1 > MAX_EPISODES_PER_LINE) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.TOO_MANY_EPISODES);
        }
        String[] parts = rawUrlText.split(EPISODE_SEPARATOR, -1);
        List<AppleCmsLineEntry> entries = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            entries.add(readEpisodeEntry(i + 1, parts[i]));
        }
        return List.copyOf(entries);
    }

    private AppleCmsLineEntry readEpisodeEntry(int snapshotOrdinal, String rawEntry) {
        if (rawEntry.isBlank()) {
            // 尾部 '#'、'##' 或纯空白段：标签为空，也说明上游结构已经不完整。
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.EMPTY_EPISODE_LABEL);
        }
        int separator = rawEntry.indexOf(LABEL_ADDRESS_SEPARATOR);
        if (separator < 0) {
            // 没有 '$' 就没有地址部分，不能把整段当成"只有标签的集"。
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.EMPTY_EPISODE_ADDRESS);
        }
        String episodeLabel = rawEntry.substring(0, separator);
        String address = rawEntry.substring(separator + 1);
        if (episodeLabel.isBlank()) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.EMPTY_EPISODE_LABEL);
        }
        if (episodeLabel.length() > MAX_EPISODE_LABEL_LENGTH) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.SNAPSHOT_TEXT_TOO_LONG);
        }
        if (address.isBlank()) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.EMPTY_EPISODE_ADDRESS);
        }
        if (address.length() > MAX_PLAY_ADDRESS_LENGTH) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.EPISODE_ADDRESS_TOO_LONG);
        }
        try {
            allowedUrlPolicy.validate(address);
        } catch (ExternalVideoUrlPolicy.RejectedUrlException rejected) {
            // 重新抛一个不带 cause 的固定码异常：不把地址带进异常链，对调用方只暴露一个稳定的适配器错误面
            // （不带任何原始数据）。策略细分原因（host 未在允许列表 / 不是 HTTPS 等）属于后续接线时另行决定的事，
            // 本切片不新增异常维度。
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.EPISODE_ADDRESS_INVALID);
        }
        return new AppleCmsLineEntry(snapshotOrdinal, episodeLabel, address, AppleCmsPlaybackState.UNVERIFIED);
    }

    // ------------------------------------------------------------------ 字段读取

    private static void readSuccessCode(JsonParser parser, JsonToken valueToken) throws IOException {
        if (valueToken != JsonToken.VALUE_STRING && valueToken != JsonToken.VALUE_NUMBER_INT) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.UPSTREAM_CODE_INVALID);
        }
        if (!SUCCESS_CODE.equals(parser.getText())) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.UPSTREAM_CODE_NOT_SUCCESS);
        }
    }

    private static String readWorkIdentity(JsonParser parser, JsonToken valueToken) throws IOException {
        String literal = readIntegerLiteral(parser, valueToken,
                AppleCmsParseException.FailureCode.WORK_IDENTITY_INVALID);
        if (literal.isEmpty() || literal.length() > MAX_WORK_IDENTITY_LENGTH) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.WORK_IDENTITY_INVALID);
        }
        // 作品身份是"正整数"，保留为十进制字符串：不转 long，就没有"溢出后变成负数"的坑。
        if (!isCanonicalUnsigned(literal) || literal.charAt(0) == '0') {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.WORK_IDENTITY_INVALID);
        }
        return literal;
    }

    private static String readTitle(JsonParser parser, JsonToken valueToken) throws IOException {
        String text = requireString(parser, valueToken, AppleCmsParseException.FailureCode.TITLE_INVALID);
        if (text.isBlank()) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.TITLE_INVALID);
        }
        if (text.length() > MAX_TITLE_LENGTH) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.SNAPSHOT_TEXT_TOO_LONG);
        }
        return text;
    }

    private static String readCategoryId(JsonParser parser, JsonToken valueToken) throws IOException {
        String literal = readIntegerLiteral(parser, valueToken,
                AppleCmsParseException.FailureCode.CATEGORY_INVALID);
        // 允许规范数值 0；具体上游语义未经验证，不推断它表示未分类或不筛选。
        if (literal.length() > MAX_CATEGORY_ID_LENGTH || !isCanonicalUnsigned(literal)) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.CATEGORY_INVALID);
        }
        return literal;
    }

    private static String readCategoryText(JsonParser parser, JsonToken valueToken) throws IOException {
        if (valueToken == JsonToken.VALUE_NULL) {
            return null;
        }
        String text = requireString(parser, valueToken, AppleCmsParseException.FailureCode.CATEGORY_INVALID);
        if (text.isBlank()) {
            return null;
        }
        if (text.length() > MAX_CATEGORY_TEXT_LENGTH) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.SNAPSHOT_TEXT_TOO_LONG);
        }
        return text;
    }

    /**
     * 作品级时长<b>文本提示</b>：文本或数字字面量原样保留（长度上限 64），其它类型一律视为未知。
     *
     * <p>绝不换算成秒、绝不除以条目数、绝不写 0；"45分钟" 就是 "45分钟"。</p>
     */
    private static String readDurationHint(JsonParser parser, JsonToken valueToken, int depth) throws IOException {
        if (valueToken == JsonToken.VALUE_STRING
                || valueToken == JsonToken.VALUE_NUMBER_INT
                || valueToken == JsonToken.VALUE_NUMBER_FLOAT) {
            String text = parser.getText();
            if (text.isBlank()) {
                return null;
            }
            if (text.length() > MAX_DURATION_HINT_LENGTH) {
                throw new AppleCmsParseException(AppleCmsParseException.FailureCode.SNAPSHOT_TEXT_TOO_LONG);
            }
            return text;
        }
        // JSON null / boolean / object / array：不是可用的时长提示，消费掉子树后按未知处理。
        skipValue(parser, valueToken, depth);
        return null;
    }

    private static String readPlayLineText(JsonParser parser, JsonToken valueToken) throws IOException {
        if (valueToken == JsonToken.VALUE_NULL) {
            return null;
        }
        return requireString(parser, valueToken, AppleCmsParseException.FailureCode.PLAY_LINES_INVALID);
    }

    private static String requireString(JsonParser parser, JsonToken valueToken,
            AppleCmsParseException.FailureCode invalidCode) throws IOException {
        if (valueToken != JsonToken.VALUE_STRING) {
            throw new AppleCmsParseException(invalidCode);
        }
        return parser.getText();
    }

    private static String readIntegerLiteral(JsonParser parser, JsonToken valueToken,
            AppleCmsParseException.FailureCode invalidCode) throws IOException {
        // 只接受 JSON 整数与字符串两种写法；VALUE_NUMBER_FLOAT（含 1.0、1e3）一律拒绝。
        if (valueToken == JsonToken.VALUE_STRING || valueToken == JsonToken.VALUE_NUMBER_INT) {
            return parser.getText();
        }
        throw new AppleCmsParseException(invalidCode);
    }

    private static long readPaginationValue(JsonParser parser, JsonToken valueToken, boolean positiveOnly)
            throws IOException {
        String literal = readIntegerLiteral(parser, valueToken,
                AppleCmsParseException.FailureCode.PAGINATION_INVALID);
        if (!isCanonicalUnsigned(literal)) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.PAGINATION_INVALID);
        }
        if (positiveOnly && literal.charAt(0) == '0') {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.PAGINATION_INVALID);
        }
        try {
            return Long.parseLong(literal);
        } catch (NumberFormatException overflow) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.PAGINATION_INVALID);
        }
    }

    /**
     * 统计 {@code separator} 在 {@code text} 里出现的次数（不重叠、从左到右）。
     *
     * <p>用它做“先数后切”：{@code split(..., -1)} 的段数恒等于出现次数 + 1，所以计数结果和切分结果一致，
     * 上限判断可以放在分配之前。{@code separator} 只接受本类自己的字面分隔符，不做正则解释。</p>
     */
    private static int countOccurrences(String text, String separator) {
        int count = 0;
        int from = text.indexOf(separator);
        while (from >= 0) {
            count++;
            from = text.indexOf(separator, from + separator.length());
        }
        return count;
    }

    /** 规范非负十进制：只有数字、无符号、无空白、无前导零（{@code "0"} 本身允许）、无小数/科学计数。 */
    private static boolean isCanonicalUnsigned(String literal) {
        if (literal.isEmpty()) {
            return false;
        }
        if (literal.length() > 1 && literal.charAt(0) == '0') {
            return false;
        }
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 结构工具

    private static byte[] requireBody(byte[] body) {
        if (body == null || body.length == 0) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.BODY_EMPTY);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.BODY_TOO_LARGE);
        }
        return body;
    }

    private static void requireToken(JsonToken token, JsonToken expected,
            AppleCmsParseException.FailureCode code) {
        if (token == null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.MALFORMED_JSON);
        }
        if (token != expected) {
            throw new AppleCmsParseException(code);
        }
    }

    private static void requireNoTrailingContent(JsonParser parser) throws IOException {
        if (parser.nextToken() != null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.TRAILING_CONTENT);
        }
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_JSON_DEPTH) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.DEPTH_EXCEEDED);
        }
    }

    private static void requireFieldName(JsonToken token) {
        if (token != JsonToken.FIELD_NAME) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.MALFORMED_JSON);
        }
    }

    private static void requireNewField(Set<String> seenFields, String name) {
        if (!seenFields.add(name)) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.DUPLICATE_FIELD);
        }
    }

    /**
     * 跳过未知字段的值：仍然完整走一遍 token，检查嵌套深度与重复字段名，但不解释内容。
     *
     * <p>为什么不用 {@code JsonParser#skipChildren()}：它不做重复键与深度检查，而"未知字段"恰恰是最容易
     * 塞进超大/超深结构的地方。这里的遍历只做结构校验，不读取、不保留任何文本。</p>
     */
    private static void skipValue(JsonParser parser, JsonToken valueToken, int depth) throws IOException {
        if (valueToken == null) {
            throw new AppleCmsParseException(AppleCmsParseException.FailureCode.MALFORMED_JSON);
        }
        switch (valueToken) {
            case START_OBJECT -> skipObject(parser, depth);
            case START_ARRAY -> skipArray(parser, depth);
            case VALUE_STRING, VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT, VALUE_TRUE, VALUE_FALSE, VALUE_NULL -> {
                // 标量：无需处理，也不保留内容。
            }
            default -> throw new AppleCmsParseException(AppleCmsParseException.FailureCode.MALFORMED_JSON);
        }
    }

    private static void skipObject(JsonParser parser, int depth) throws IOException {
        requireDepth(depth);
        Set<String> seenFields = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireFieldName(parser.currentToken());
            requireNewField(seenFields, parser.currentName());
            skipValue(parser, parser.nextToken(), depth + 1);
        }
    }

    private static void skipArray(JsonParser parser, int depth) throws IOException {
        requireDepth(depth);
        while (true) {
            JsonToken itemToken = parser.nextToken();
            if (itemToken == JsonToken.END_ARRAY) {
                return;
            }
            skipValue(parser, itemToken, depth + 1);
        }
    }
}
