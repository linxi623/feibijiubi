package com.feibijiubi.backend.videosource.integration.applecms;

import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsResponseMismatchException.FailureCode;
import com.feibijiubi.backend.videosource.integration.transport.RestrictedCmsHttpClient;

/**
 * E2-D：把已有的<b>受限 GET 客户端</b>（{@link RestrictedCmsHttpClient}）和<b>离线解析器</b>
 * （{@link AppleCmsJsonAdapter}）组合成"一次请求 → 一次解析 → 一次响应一致性校验"的<b>纯应用客户端</b>。
 *
 * <h2>一次调用只做三步</h2>
 * <ol>
 *   <li><b>取 body</b>：直接调用 {@link RestrictedCmsHttpClient}，请求形态（base、query 参数名与顺序、
 *       大小上限、超时、TLS、DNS 钩子、禁跳转/禁代理/禁压缩/禁隐式重试）全部由它决定，本类<b>不</b>拼 URL、
 *       不设 header、不改写参数。</li>
 *   <li><b>解析</b>：把拿到的 {@code byte[]} 交给 {@link AppleCmsJsonAdapter}，结构、上限、地址语法与
 *       允许端点校验全部由它决定。</li>
 *   <li><b>核对响应是不是这次请求的回答</b>：页码、分类、作品身份三处逐项比对，见下。</li>
 * </ol>
 *
 * <h2>为什么必须有第三步</h2>
 * <p>解析器只拿到 body，<b>拿不到请求参数</b>（{@link AppleCmsJsonAdapter#parseDetail(byte[])} 的文档已经
 * 明确写了这一点）。因此"解析成功"只证明这份字节符合结构契约，<b>不</b>证明它是我们请求的那一页、那一类、
 * 那一条作品。上游可能缓存串页、按权限返回别的作品、或者干脆返回一份结构合法的错数据；不比对就把结果交出去，
 * 等于把错误作品绑定到请求的身份上。所以本类把比对放在<b>返回之前</b>，不一致就整份拒绝。</p>
 *
 * <h2>它刻意不做什么（这一条就是本切片的范围）</h2>
 * <ul>
 *   <li><b>不是 Spring 组件、不读配置、不打日志</b>：没有 {@code @Component}/{@code @Service}，不会被组件
 *       扫描注入到任何现有链路；没有日志语句，因此不会把真实 source URL、标题、分类名或地址写进日志。</li>
 *   <li><b>不自动翻页、不重试、不起后台线程</b>：一次调用就是一次请求 + 一次解析。翻页循环、失败退避、
 *       并发执行器都属于后续同步任务（E4），必须显式设计，不能由本类偷偷替调用方做。</li>
 *   <li><b>不是通用 HTTP 框架</b>：只有两个入口，分别对应上游两个固定 API 形态。没有任意 method、
 *       任意 header、任意 URL 透传，也没有"再包一层可替换的 transport 抽象"——构造参数就是全部 IO 与解析
 *       入口，本类不提供默认实现，也不做任何降级（不存在"解析失败就返回空快照"的分支）。</li>
 *   <li><b>不碰数据库、不持久化、不发布、不创建可播放结论</b>：返回值是不可变快照。</li>
 *   <li><b>不合成任何缺失字段</b>：不造剧集 ID、不把作品级时长文本换算成秒、不把
 *       {@link AppleCmsPlaybackState#UNVERIFIED} 提升成"可播放"。</li>
 * </ul>
 *
 * <h2>三处响应一致性校验</h2>
 * <ul>
 *   <li><b>页码</b>：列表响应自报的 {@code page} 必须<b>等于</b>请求的页码，否则
 *       {@link FailureCode#RESPONSE_PAGE_MISMATCH}。用"等于"而不是"不小于"或"相差不超过 1"：串页时任何
 *       容忍都会让调用方把第 2 页的条目当成第 1 页。</li>
 *   <li><b>分类</b>：请求指定了分类时，本页<b>每一条</b>条目的 {@code type_id} 都必须等于请求分类的规范
 *       十进制形式（{@code 0} 仅作为合法数值接受，上游含义未验证；不筛选必须传 {@code null}），任何一条不同、或条目缺少分类，就整页失败
 *       （{@link FailureCode#RESPONSE_CATEGORY_MISMATCH}），<b>不</b>返回"过滤后的部分结果"。逐条跳过是
 *       调用方的选择，不能由本类静默替它做。请求<b>没有</b>指定分类时，本类<b>不</b>做任何过滤，原样返回
 *       快照里的全部条目。</li>
 *   <li><b>作品身份</b>：详情响应的 {@link AppleCmsDetailSnapshot#workIdentity()} 必须<b>精确等于</b>
 *       请求的作品 ID，否则 {@link FailureCode#RESPONSE_IDENTITY_MISMATCH}，绝不把错误作品绑定成请求结果。</li>
 * </ul>
 * <p>空页是合法的：{@code total=0}/{@code pagecount=0}/{@code page=1} 加一个空 {@code list} 会正常返回空
 * 条目列表 —— 这是解析器已有的规则，本类不额外收紧，也不把空页当成"没有数据所以跳过校验"。</p>
 *
 * <h2>失败面（谁的原因码就是谁的原因码）</h2>
 * <ul>
 *   <li>{@link com.feibijiubi.backend.videosource.integration.transport.CmsHttpRequestException}
 *       原样上抛：不捕获、不重新分类、不改写原因码。参数越界仍由 HTTP 层抛 {@link IllegalArgumentException}，
 *       本类<b>不</b>复制一份页码/分类/作品 ID 的语法规则（两份规则迟早分叉）。</li>
 *   <li>{@link AppleCmsParseException} 原样上抛：同样不捕获、不折叠成别的原因码。</li>
 *   <li>本类自己只新增一种失败：{@link AppleCmsResponseMismatchException}（固定码、无 cause、不含上游文本）。</li>
 * </ul>
 * <p>两条既有链路都已经保证异常里不带 URL、host、地址、响应体或上游自由文本；本类<b>不</b>捕获它们再包一层，
 * 所以不会新增任何"把真实 source URL 或响应内容带进异常/日志"的路径。</p>
 *
 * <h2>返回值仍然只是未验证快照</h2>
 * <p>三处校验仅确认响应自报字段与请求一致；空页没有分类条目可核对，字段一致不等于证明响应真实对应请求，<b>不</b>证明内容已授权、标题正确、分类映射正确、地址
 * 可播放或剧集可绑定。调用方（后续的管理端/同步任务）仍需<b>人工映射与审核</b>，并且不能把这些快照直接
 * 当可信数据持久化。</p>
 *
 * <h2>线程安全</h2>
 * <p>本类不可变（两个字段都是构造期赋值的 {@code final}），不持有 per-request 状态；可共享性继承自两个依赖
 * （受限客户端与解析器都声明为可被多线程共享）。本类不引入任何缓存或静态可变状态。</p>
 */
public final class AppleCmsSourceClient {

    private final RestrictedCmsHttpClient httpClient;
    private final AppleCmsJsonAdapter adapter;

    /**
     * @param httpClient 受限 GET 客户端（取 body），不允许为 {@code null}：本类<b>没有</b>默认客户端，
     *                   也不会退化成"直接连上游"
     * @param adapter    苹果CMS JSON 离线解析器，不允许为 {@code null}：本类<b>没有</b>默认解析器，
     *                   也不会在解析失败时返回空快照
     * @throws IllegalArgumentException 任一参数为 {@code null}
     */
    public AppleCmsSourceClient(RestrictedCmsHttpClient httpClient, AppleCmsJsonAdapter adapter) {
        if (httpClient == null) {
            throw new IllegalArgumentException("受限 HTTP 客户端不能为空：本类没有默认客户端");
        }
        if (adapter == null) {
            throw new IllegalArgumentException("苹果CMS 解析器不能为空：本类没有默认解析器");
        }
        this.httpClient = httpClient;
        this.adapter = adapter;
    }

    /**
     * 取目录的一页并核对响应自报页码及条目分类。
     *
     * <p>顺序固定：取 body → 解析 → 校验页码 → 校验分类。页码在分类之前检查，因为"串页"是整份响应都不属于
     * 本次请求，比"这一页混入了别的分类"更靠前。</p>
     *
     * @param page       页码 {@code 1..}{@link RestrictedCmsHttpClient#MAX_LIST_PAGE}（由 HTTP 层校验）
     * @param categoryId 请求的分类，{@code null} 表示不过滤；非 {@code null} 时必须非负（由 HTTP 层校验）
     * @return 解析产物原样返回：指定了分类时是"整页都通过分类校验"的快照，未指定分类时是<b>未经过滤</b>的
     *         全部条目
     * @throws IllegalArgumentException                  页码/分类越界（来自 HTTP 层，本类不加第二份规则）
     * @throws com.feibijiubi.backend.videosource.integration.transport.CmsHttpRequestException
     *                                                   上游传输失败（固定码，原样上抛）
     * @throws AppleCmsParseException                    响应不符合解析契约（固定码，原样上抛）
     * @throws AppleCmsResponseMismatchException         页码不同，或指定分类时存在分类不同的条目
     */
    public AppleCmsListSnapshot fetchList(int page, Integer categoryId) {
        byte[] body = httpClient.fetchList(page, categoryId);
        AppleCmsListSnapshot snapshot = adapter.parseList(body);
        requireRequestedPage(snapshot, page);
        requireRequestedCategory(snapshot, categoryId);
        return snapshot;
    }

    /**
     * 取单个作品详情并核对响应自报作品 ID。
     *
     * <p>作品 ID 的语法（十进制正整数、长度上限、无前导零）由 HTTP 层校验，本类不复制这套规则；正因为 HTTP
     * 调用排在最前面，{@code requestedWorkId} 为 {@code null} 或不合规范时会在这一步就以
     * {@link IllegalArgumentException} 失败，不会走到下面的身份比对。</p>
     *
     * @param requestedWorkId 请求的上游作品 ID（规范十进制正整数，由 HTTP 层校验）
     * @return 解析产物原样返回：身份与请求一致，但线路/地址仍是 {@link AppleCmsPlaybackState#UNVERIFIED}
     *         的候选，不含剧集 ID，也不含被换算成秒的时长
     * @throws IllegalArgumentException                  作品 ID 不合规范（来自 HTTP 层）
     * @throws com.feibijiubi.backend.videosource.integration.transport.CmsHttpRequestException
     *                                                   上游传输失败（固定码，原样上抛）
     * @throws AppleCmsParseException                    响应不符合解析契约（固定码，原样上抛）
     * @throws AppleCmsResponseMismatchException         响应作品身份与请求的作品 ID 不同
     */
    public AppleCmsDetailSnapshot fetchDetail(String requestedWorkId) {
        byte[] body = httpClient.fetchDetail(requestedWorkId);
        AppleCmsDetailSnapshot snapshot = adapter.parseDetail(body);
        // 与下面的分类校验同一个写法：把"保证非空"的一侧放左边（解析器保证 workIdentity 非空）。
        if (!snapshot.workIdentity().equals(requestedWorkId)) {
            // 上游返回了另一条作品：结构合法但不是本次请求的答案，绝不绑定。
            throw new AppleCmsResponseMismatchException(FailureCode.RESPONSE_IDENTITY_MISMATCH);
        }
        return snapshot;
    }

    /** 列表响应自报的页码必须与请求页码全等；串页时任何"差不多"的容忍都会让调用方错认条目。 */
    private static void requireRequestedPage(AppleCmsListSnapshot snapshot, int requestedPage) {
        if (snapshot.pagination().page() != requestedPage) {
            throw new AppleCmsResponseMismatchException(FailureCode.RESPONSE_PAGE_MISMATCH);
        }
    }

    /**
     * 指定分类时，每条自报分类 ID 必须精确相等；父分类展开为子分类不受支持。未指定时不做过滤。
     *
     * <p>请求值用 {@link Integer#toString(int)} 规范化：非负整数的十进制形式本身就是规范形式（无前导零、
     * 无正号），所以可以直接和解析器输出的规范 {@code type_id} 字符串比较。负数在 HTTP 层已被拒绝，
     * 因此这里的规范化不会遇到"负号"这种无法与 {@code type_id} 对齐的写法。</p>
     */
    private static void requireRequestedCategory(AppleCmsListSnapshot snapshot, Integer requestedCategoryId) {
        if (requestedCategoryId == null) {
            return;
        }
        String canonicalRequestedCategoryId = Integer.toString(requestedCategoryId);
        for (AppleCmsCatalogEntry entry : snapshot.items()) {
            // 请求值放左边（已确认非空）；条目 categoryId 为 null 表示上游没给分类，
            // 无法证明它属于请求的分类，按不一致处理。
            if (!canonicalRequestedCategoryId.equals(entry.categoryId())) {
                throw new AppleCmsResponseMismatchException(FailureCode.RESPONSE_CATEGORY_MISMATCH);
            }
        }
    }
}
