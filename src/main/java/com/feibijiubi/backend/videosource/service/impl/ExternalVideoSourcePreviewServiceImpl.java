package com.feibijiubi.backend.videosource.service.impl;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.videosource.config.ExternalVideoSourceProperties;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsCatalogEntry;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsDetailSnapshot;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsLineEntry;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsListSnapshot;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsParseException;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsPlayLine;
import com.feibijiubi.backend.videosource.integration.applecms.AppleCmsResponseMismatchException;
import com.feibijiubi.backend.videosource.integration.transport.CmsHttpRequestException;
import com.feibijiubi.backend.videosource.integration.transport.RestrictedCmsHttpClient;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceCatalogClient;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceClientProvider;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceDescriptor;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceDetailClient;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourcePreviewQuotaService;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourcePreviewService;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourceRegistry;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceCatalogVO;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceDetailVO;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * M3-B：管理员外部来源预览的实现 —— <b>只读</b>、一次调用最多一个上游请求、结果脱敏。
 *
 * <h2>固定准入顺序</h2>
 * <p>目录与详情都是：uid → 参数 → 预览总开关 → 来源（存在且启用） → （仅详情）媒体策略 →
 * 本实例并发 permit → 每管理员 Redis 配额 → 上游请求。前面任何一步拒绝都不会产生上游请求；
 * permit 在 {@code finally} 释放，因此成功、上游失败、超限、Redis 失败都不会泄漏 permit。</p>
 *
 * <h2>两种"429"不是同一件事</h2>
 * <ul>
 *   <li>并发 permit 满：<b>进程内</b>信号量，只限<b>本实例</b>同时打开的上游请求数，{@code tryAcquire}
 *       立刻失败，<b>不排队</b>、不起线程；</li>
 *   <li>每管理员配额：Redis 固定窗口计数，同一部署的多个实例<b>共享</b>同一份额度。</li>
 * </ul>
 * <p>因此这里不能把两者合并成"全局并发上限"：并发是实例内的，配额是跨实例的。</p>
 *
 * <h2>不重试、不翻页、不写业务库</h2>
 * <p>没有自动翻页、没有重试退避、没有后台任务，也不写数据库、不建候选 token、不做映射/确认/探测。
 * 上游响应自报的分页数字只作为展示字段返回，<b>不</b>用来驱动下一次请求。</p>
 *
 * <h2>“只读”不包括 Redis 配额</h2>
 * <p>不写业务库、不改统计，但 {@link #catalog} / {@link #detail} 在发上游请求前会调用
 * {@code quotaService.checkAndConsume}，即向 Redis 写一次固定窗口计数（键只含登录 uid）。因此这两个方法
 * <b>不是</b>端到端 write-free；只有 {@link #listAvailableSources()} 是纯内存（它仍会先判预览总开关）。</p>
 *
 * <h2>脱敏</h2>
 * <p>返回的 VO 只有作品身份、标题、分类标识/名称、线路/条目快照位置与标签，以及"是否有作品级时长提示"。
 * 播放地址、线路键、时长原文、封面外链一律不返回；异常一律折叠成固定文案的 {@link BusinessException}
 * （不带 cause、不带上游原文、不带 URL），也不写任何快照/异常日志。</p>
 *
 * <p>这里的"不返回"只针对<b>结构性字段</b>：{@code title} / {@code categoryName} / 条目 {@code label}
 * 仍是上游<b>不可信自由文本</b>，本类<b>不</b>做任何清洗或白名单，因此不能声称它们里不含 URL 或链接。</p>
 */
@Service
public class ExternalVideoSourcePreviewServiceImpl implements ExternalVideoSourcePreviewService {

    private static final String PREVIEW_DISABLED_MESSAGE =
            REASON_PREVIEW_DISABLED + "：外部来源预览未启用";
    private static final String SOURCE_UNAVAILABLE_MESSAGE =
            REASON_SOURCE_UNAVAILABLE + "：来源未配置或未启用";
    private static final String MEDIA_POLICY_REQUIRED_MESSAGE =
            REASON_MEDIA_POLICY_REQUIRED + "：该来源没有已批准的媒体端点，不提供详情预览";
    private static final String CONCURRENCY_LIMIT_MESSAGE =
            REASON_CONCURRENCY_LIMIT + "：本实例同时进行的预览请求已达上限，请稍后再试";
    private static final String UPSTREAM_TRANSPORT_FAILED_MESSAGE =
            REASON_UPSTREAM_TRANSPORT_FAILED + "：上游请求失败，未返回上游原文";
    private static final String UPSTREAM_RESPONSE_INVALID_MESSAGE =
            REASON_UPSTREAM_RESPONSE_INVALID + "：上游响应不符合解析契约，未返回上游原文";
    private static final String UPSTREAM_RESPONSE_MISMATCH_MESSAGE =
            REASON_UPSTREAM_RESPONSE_MISMATCH + "：上游响应与本次请求不一致，已整份拒绝";

    private static final String PAGE_REQUIRED_MESSAGE =
            "page 必须在 " + MIN_PAGE + " 到 " + RestrictedCmsHttpClient.MAX_LIST_PAGE + " 之间";
    private static final String CATEGORY_REQUIRED_MESSAGE = "categoryId 必须是非负整数，或省略";
    private static final String WORK_ID_REQUIRED_MESSAGE =
            "workId 必须是长度 1.." + RestrictedCmsHttpClient.MAX_WORK_ID_LENGTH
                    + " 的十进制正整数（不允许前导零）";

    private final ExternalVideoSourceRegistry registry;
    private final ExternalVideoSourceClientProvider clientProvider;
    private final ExternalVideoSourcePreviewQuotaService quotaService;

    /**
     * 本实例的上游并发闸门。
     *
     * <p>许可数来自 {@code app.external-video-source.max-concurrent-requests}（启动期校验 1..8）。
     * 非公平信号量：{@code tryAcquire} 是"现在有空位吗"的判断，不存在排队语义，公平性没有意义。</p>
     */
    private final Semaphore upstreamPermits;

    public ExternalVideoSourcePreviewServiceImpl(
            ExternalVideoSourceRegistry registry,
            ExternalVideoSourceClientProvider clientProvider,
            ExternalVideoSourcePreviewQuotaService quotaService,
            ExternalVideoSourceProperties properties
    ) {
        this.registry = registry;
        this.clientProvider = clientProvider;
        this.quotaService = quotaService;
        this.upstreamPermits = new Semaphore(properties.getMaxConcurrentRequests());
    }

    @Override
    public List<ExternalVideoSourceVO> listAvailableSources() {
        requirePreviewEnabled();
        return registry.listAvailableSources().stream()
                .map(source -> new ExternalVideoSourceVO(source.code(), source.name()))
                .toList();
    }

    @Override
    public ExternalVideoSourceCatalogVO catalog(Integer adminUserId,
                                                String sourceCode,
                                                int page,
                                                Integer categoryId) {
        requireAdminUserId(adminUserId);
        requirePage(page);
        requireCategoryId(categoryId);
        ExternalVideoSourceDescriptor descriptor = requireDescriptor(sourceCode);

        acquirePermit();
        try {
            quotaService.checkAndConsume(adminUserId);
            ExternalVideoSourceCatalogClient client = clientProvider.createCatalogClient(descriptor);
            return toCatalog(descriptor.code(), page, fetchCatalogPage(client, page, categoryId));
        } finally {
            upstreamPermits.release();
        }
    }

    @Override
    public ExternalVideoSourceDetailVO detail(Integer adminUserId,
                                             String sourceCode,
                                             String workId) {
        requireAdminUserId(adminUserId);
        requireWorkId(workId);
        ExternalVideoSourceDescriptor descriptor = requireDescriptor(sourceCode);
        if (descriptor.mediaPolicy().isEmpty()) {
            // 没有媒体授权就不打开详情端点：绝不回退到 API 允许列表，也不放行任意地址。
            throw new BusinessException(503, MEDIA_POLICY_REQUIRED_MESSAGE);
        }

        acquirePermit();
        try {
            quotaService.checkAndConsume(adminUserId);
            ExternalVideoSourceDetailClient client = clientProvider.createDetailClient(descriptor);
            return toDetail(descriptor.code(), fetchDetail(client, workId));
        } finally {
            upstreamPermits.release();
        }
    }

    // ------------------------------------------------------------------ 准入与失败分类

    private static void requireAdminUserId(Integer adminUserId) {
        if (adminUserId == null) {
            throw new BusinessException(401, "未登录：预览只接受登录态管理员，不接受客户端传入的 uid");
        }
    }

    private static void requirePage(int page) {
        if (page < MIN_PAGE || page > RestrictedCmsHttpClient.MAX_LIST_PAGE) {
            throw new BusinessException(400, PAGE_REQUIRED_MESSAGE);
        }
    }

    private static void requireCategoryId(Integer categoryId) {
        if (categoryId != null && categoryId < 0) {
            throw new BusinessException(400, CATEGORY_REQUIRED_MESSAGE);
        }
    }

    /**
     * 作品 ID 语法：与传输层同一套规则（用同一个长度常量，避免两处规则分叉），提前判成 400。
     *
     * <p>不把这个判断留给传输层：那里抛的是 {@link IllegalArgumentException}，会被全局处理器当成 500，
     * 而"客户端传了不合法的作品 ID"明确是 400。</p>
     */
    private static void requireWorkId(String workId) {
        if (workId == null || workId.isEmpty()
                || workId.length() > RestrictedCmsHttpClient.MAX_WORK_ID_LENGTH
                || workId.charAt(0) == '0') {
            throw new BusinessException(400, WORK_ID_REQUIRED_MESSAGE);
        }
        for (int i = 0; i < workId.length(); i++) {
            char c = workId.charAt(i);
            if (c < '0' || c > '9') {
                throw new BusinessException(400, WORK_ID_REQUIRED_MESSAGE);
            }
        }
    }

    private void requirePreviewEnabled() {
        if (!registry.previewEnabled()) {
            throw new BusinessException(503, PREVIEW_DISABLED_MESSAGE);
        }
    }

    /**
     * 预览总开关 + 来源存在且启用。
     *
     * <p>未配置、已禁用、{@code sourceCode} 为 null 或不是已批准的引用键，一律得到同一个 404 文案，因此调用方
     * 无法通过错误区分"这个 code 存在但被禁用"与"根本没配置"，也就无法枚举部署里的来源。{@code sourceCode}
     * <b>没有</b>单独的语法校验分支（不会因为格式不合法变成 400）：所有查不到的取值都走这条 404。注册表的
     * {@link IllegalStateException} 只按<b>固定文案</b>识别，其它未知故障原样上抛（不当成 404 掩盖 bug）。</p>
     */
    private ExternalVideoSourceDescriptor requireDescriptor(String sourceCode) {
        requirePreviewEnabled();
        try {
            return registry.requirePreviewSource(sourceCode);
        } catch (IllegalStateException e) {
            if (ExternalVideoSourceRegistry.SOURCE_UNAVAILABLE_MESSAGE.equals(e.getMessage())) {
                throw new BusinessException(404, SOURCE_UNAVAILABLE_MESSAGE);
            }
            throw e;
        }
    }

    private void acquirePermit() {
        if (!upstreamPermits.tryAcquire()) {
            throw new BusinessException(429, CONCURRENCY_LIMIT_MESSAGE);
        }
    }

    private static AppleCmsListSnapshot fetchCatalogPage(ExternalVideoSourceCatalogClient client,
                                                         int page,
                                                         Integer categoryId) {
        try {
            return client.fetchPage(page, categoryId);
        } catch (CmsHttpRequestException e) {
            throw new BusinessException(502, UPSTREAM_TRANSPORT_FAILED_MESSAGE);
        } catch (AppleCmsParseException e) {
            throw new BusinessException(502, UPSTREAM_RESPONSE_INVALID_MESSAGE);
        } catch (AppleCmsResponseMismatchException e) {
            throw new BusinessException(502, UPSTREAM_RESPONSE_MISMATCH_MESSAGE);
        }
    }

    private static AppleCmsDetailSnapshot fetchDetail(ExternalVideoSourceDetailClient client,
                                                      String workId) {
        try {
            return client.fetchWork(workId);
        } catch (CmsHttpRequestException e) {
            throw new BusinessException(502, UPSTREAM_TRANSPORT_FAILED_MESSAGE);
        } catch (AppleCmsParseException e) {
            throw new BusinessException(502, UPSTREAM_RESPONSE_INVALID_MESSAGE);
        } catch (AppleCmsResponseMismatchException e) {
            throw new BusinessException(502, UPSTREAM_RESPONSE_MISMATCH_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ 脱敏映射

    private static ExternalVideoSourceCatalogVO toCatalog(String sourceCode,
                                                          int page,
                                                          AppleCmsListSnapshot snapshot) {
        List<ExternalVideoSourceCatalogVO.Item> items = snapshot.items().stream()
                .map(ExternalVideoSourcePreviewServiceImpl::toCatalogItem)
                .toList();
        return new ExternalVideoSourceCatalogVO(
                sourceCode,
                page,
                snapshot.pagination().pageCount(),
                snapshot.pagination().total(),
                items);
    }

    private static ExternalVideoSourceCatalogVO.Item toCatalogItem(AppleCmsCatalogEntry entry) {
        return new ExternalVideoSourceCatalogVO.Item(
                entry.workIdentity(),
                entry.title(),
                entry.categoryId(),
                entry.categoryName());
    }

    private static ExternalVideoSourceDetailVO toDetail(String sourceCode,
                                                        AppleCmsDetailSnapshot snapshot) {
        List<ExternalVideoSourceDetailVO.Line> lines = snapshot.playLines().stream()
                .map(ExternalVideoSourcePreviewServiceImpl::toDetailLine)
                .toList();
        return new ExternalVideoSourceDetailVO(
                sourceCode,
                snapshot.workIdentity(),
                snapshot.title(),
                snapshot.categoryId(),
                snapshot.categoryName(),
                // 只暴露"有没有"这段文本，不暴露原文：它是作品级文本提示，不是单集时长。
                snapshot.workDurationHint() != null,
                lines);
    }

    private static ExternalVideoSourceDetailVO.Line toDetailLine(AppleCmsPlayLine line) {
        List<ExternalVideoSourceDetailVO.Entry> entries = line.entries().stream()
                .map(ExternalVideoSourcePreviewServiceImpl::toDetailEntry)
                .toList();
        return new ExternalVideoSourceDetailVO.Line(line.snapshotOrdinal(), entries);
    }

    private static ExternalVideoSourceDetailVO.Entry toDetailEntry(AppleCmsLineEntry entry) {
        // 只保留快照位置与标签：地址（可能带签名）与线路键都不出接口。
        return new ExternalVideoSourceDetailVO.Entry(entry.snapshotOrdinal(), entry.episodeLabel());
    }
}
