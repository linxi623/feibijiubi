package com.feibijiubi.backend.videosource.service;

import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceCatalogVO;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceDetailVO;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceVO;

import java.util.List;

/**
 * M3-B：管理员<b>只读预览</b>外部来源的目录与作品详情。
 *
 * <h2>它做什么</h2>
 * <ol>
 *   <li>{@link #listAvailableSources()}：列出当前可预览来源的<b>安全摘要</b>（只有 code 与名称），
 *       纯内存查注册表，<b>不发上游请求、不消耗配额</b>。</li>
 *   <li>{@link #catalog} / {@link #detail}：按部署批准配置向<b>一个</b>上游端点取一页/一条，映射成
 *       脱敏 VO 返回。</li>
 * </ol>
 *
 * <h2>固定的准入顺序（不要调整）</h2>
 * <p>两个上游端点都是：参数校验 → 预览总开关 → 来源存在且启用 → （仅详情）媒体策略存在 →
 * 本实例并发 permit → 每管理员 Redis 配额 → 真正发请求。任何一步拒绝都<b>不会</b>产生上游请求；
 * permit 在 {@code finally} 里释放，因此所有分支（成功、上游失败、超限、Redis 失败）都不会泄漏 permit。</p>
 *
 * <h2>它刻意不做什么</h2>
 * <ul>
 *   <li>不自动翻页、不重试、不起后台线程、不排队：一次调用最多一个上游请求，拿不到 permit 立刻 429；</li>
 *   <li>不建候选 token、不建映射、不确认时长、不探测媒体、不创建/修改 {@code video} 或统计行；</li>
 *   <li>不返回播放地址、线路键、作品级时长原文、封面外链或任何上游原始字段，也不把快照/异常原文写进日志；</li>
 *   <li>不提供 base、header、auth、任意 URL 的透传，也不接受客户端传入 uid。</li>
 * </ul>
 *
 * <h2>“只读”的准确含义（不要扩大成端到端无写入）</h2>
 * <ul>
 *   <li><b>不写业务库</b>：不创建/修改 {@code video}、映射、候选 token 或任何统计行，也不改
 *       {@code video_status}；</li>
 *   <li>但 {@link #catalog} / {@link #detail} 在真正发上游请求之前会消耗<b>每管理员 Redis 配额</b>
 *       （{@code fixed-window-rate-limit.lua} 的计数写入，键只含登录 uid），因此这两个方法<b>不是</b>
 *       端到端 write-free；{@link #listAvailableSources()} 是纯内存，不碰 Redis。</li>
 * </ul>
 *
 * <h2>返回文本的信任级别</h2>
 * <p>VO 只保证不返回结构性敏感字段（播放地址、线路键、时长原文、上游 base 与允许列表）；上游给的
 * {@code title} / {@code categoryName} / 分集 {@code label} 仍是<b>不可信自由文本</b>。本片没有加任何
 * 清洗/白名单，因此<b>不能</b>声称这些文本里不含 URL 或链接，渲染转义由前端负责。</p>
 *
 * <h2>错误契约（code 即 HTTP 状态）</h2>
 * <ul>
 *   <li>400 参数不合法（页码、分类 ID、作品 ID）；</li>
 *   <li>401 缺少登录态 uid；</li>
 *   <li>404 来源未配置、未启用，或 {@code sourceCode} 不是已批准的引用键（三种情况共用同一文案，
 *       无法枚举部署里配了什么）。{@code sourceCode} <b>没有</b>单独的语法校验分支：任何查不到的
 *       取值都走这里，不会被判成 400；</li>
 *   <li>429 超过并发 permit 或超过每管理员配额；</li>
 *   <li>502 上游传输失败 / 响应不可解析 / 响应与请求不一致（三者固定文案，不带 URL、响应体或 cause）；</li>
 *   <li>503 预览总开关关闭、缺少媒体策略、Redis 不可用。</li>
 * </ul>
 */
public interface ExternalVideoSourcePreviewService {

    /** 页码下限。 */
    int MIN_PAGE = 1;

    /** 预览总开关关闭（固定机器码，不含配置取值）。 */
    String REASON_PREVIEW_DISABLED = "PREVIEW_DISABLED";

    /** 来源未配置、未启用或 code 不是已批准的引用键：三种情况共用，避免枚举部署配置。 */
    String REASON_SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";

    /** 该来源没有批准媒体端点，因此不提供详情预览。 */
    String REASON_MEDIA_POLICY_REQUIRED = "MEDIA_POLICY_REQUIRED";

    /** 本实例并发 permit 已用满（不排队）。 */
    String REASON_CONCURRENCY_LIMIT = "PREVIEW_CONCURRENCY_LIMIT";

    /** 上游传输失败（DNS/TLS/连接/状态码/超限/超预算），固定文案。 */
    String REASON_UPSTREAM_TRANSPORT_FAILED = "UPSTREAM_TRANSPORT_FAILED";

    /** 上游响应不符合解析契约，固定文案。 */
    String REASON_UPSTREAM_RESPONSE_INVALID = "UPSTREAM_RESPONSE_INVALID";

    /** 上游响应自报的页码/分类/作品身份与请求不一致，固定文案。 */
    String REASON_UPSTREAM_RESPONSE_MISMATCH = "UPSTREAM_RESPONSE_MISMATCH";

    /**
     * 当前可预览来源的安全摘要。
     *
     * @return 只有 code 与名称的列表；预览关闭时抛 503 而不是返回空列表
     * @throws com.feibijiubi.backend.common.BusinessException 503 预览未启用
     */
    List<ExternalVideoSourceVO> listAvailableSources();

    /**
     * 取一个来源的目录一页。
     *
     * @param adminUserId 登录态 uid（配额维度）
     * @param sourceCode  部署白名单引用键
     * @param page        页码，从 {@value #MIN_PAGE} 开始
     * @param categoryId  上游分类 ID，{@code null} 表示不过滤
     * @return 脱敏目录 VO（作品身份、标题、分类标识/名称与上游自报分页数字）
     * @throws com.feibijiubi.backend.common.BusinessException 400/401/404/429/502/503，见类注释
     */
    ExternalVideoSourceCatalogVO catalog(Integer adminUserId, String sourceCode, int page, Integer categoryId);

    /**
     * 取一个来源的单个作品详情。
     *
     * @param adminUserId 登录态 uid（配额维度）
     * @param sourceCode  部署白名单引用键
     * @param workId      上游作品 ID（规范十进制正整数，无前导零）
     * @return 脱敏详情 VO（作品元数据、线路/条目快照位置与标签，以及"是否有作品级时长提示"）
     * @throws com.feibijiubi.backend.common.BusinessException 400/401/404/429/502/503，见类注释
     */
    ExternalVideoSourceDetailVO detail(Integer adminUserId, String sourceCode, String workId);
}
