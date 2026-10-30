package com.feibijiubi.backend.videosource.controller;

import com.feibijiubi.backend.auth.annotation.AdminOnly;
import com.feibijiubi.backend.shared.ApiResponse;
import com.feibijiubi.backend.videosource.service.ExternalVideoSourcePreviewService;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceCatalogVO;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceDetailVO;
import com.feibijiubi.backend.videosource.vo.ExternalVideoSourceVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * M3-B：管理员<b>外部来源只读预览</b>接口。
 *
 * <h2>三个 GET</h2>
 * <ul>
 *   <li>{@code GET /api/admin/video-sources}：可预览来源的安全摘要（code + 名称），纯内存，不消耗配额；</li>
 *   <li>{@code GET /api/admin/video-sources/{sourceCode}/catalog?page=1[&categoryId=]}：目录一页；</li>
 *   <li>{@code GET /api/admin/video-sources/{sourceCode}/detail?workId=}：单个作品详情。</li>
 * </ul>
 *
 * <h2>安全边界</h2>
 * <ul>
 *   <li>路径在既有 {@code /api/admin/**} 下，因此由真实的 {@code LoginInterceptor}（401）与
 *       {@code AdminInterceptor}（403）拦截；方法上标注 {@link AdminOnly} 与既有约定一致；</li>
 *   <li>操作人只取登录态 {@code currentUserId}，<b>没有</b>任何 uid 请求参数；缺失由服务层 401 拒绝；</li>
 *   <li>没有 base、header、auth、任意 URL 的透传参数：请求目标只能来自部署批准的描述符；</li>
 *   <li>没有业务写入口（不建视频、不建映射、不确认时长、不探测媒体），也不改任何统计行；没有分页自动循环。</li>
 * </ul>
 *
 * <p>"只读"的范围要说准：业务库与统计都不写，但 {@code catalog} / {@code detail} 会在发上游请求前向 Redis
 * 写一次每管理员固定窗口计数（配额），因此这两个端点<b>不是</b>端到端 write-free；只有列表接口是纯内存。
 * 配额的具体实现见 {@code ExternalVideoSourcePreviewQuotaService}。</p>
 *
 * <h2>响应不被缓存</h2>
 * <p>三个 GET 都在<b>调用服务层之前</b>写下 {@code Cache-Control: no-store}，因此成功响应与服务层自己抛出的
 * 业务错误（例如 503 预览未启用）都带这条 header。边界要写清：401/403 由拦截器在进入本类之前结束请求，
 * 那时还没有任何 controller 代码执行，所以<b>不能</b>声称"本接口的所有错误响应都带 no-store"；本片也没有
 * 加 {@code ResponseBodyAdvice} 之类会改变全局行为的改动。</p>
 *
 * <h2>返回文本的信任级别</h2>
 * <p>响应只保证<b>不含结构性敏感字段</b>（播放地址、线路键、时长原文、上游 base 与允许列表）。上游给的
 * {@code title} / {@code categoryName} / 分集 {@code label} 仍是<b>不可信自由文本</b>：本片没有加任何
 * 清洗或白名单，因此<b>不能</b>声称这些文本里不含 URL 或链接，渲染转义由前端负责。</p>
 *
 * <p>参数取值范围由服务层统一校验（400），本类只负责把请求参数与登录态交给服务层；错误码到 HTTP 状态的
 * 映射沿用全局异常处理器（{@code BusinessException.code} 即 HTTP 状态）。</p>
 */
@RestController
@RequestMapping("/api/admin/video-sources")
@RequiredArgsConstructor
public class ExternalVideoSourceAdminController {

    private static final String CACHE_CONTROL_NO_STORE = "no-store";

    private final ExternalVideoSourcePreviewService previewService;

    @AdminOnly
    @GetMapping
    public ApiResponse<List<ExternalVideoSourceVO>> list(HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(previewService.listAvailableSources());
    }

    @AdminOnly
    @GetMapping("/{sourceCode}/catalog")
    public ApiResponse<ExternalVideoSourceCatalogVO> catalog(
            @PathVariable("sourceCode") String sourceCode,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "categoryId", required = false) Integer categoryId,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        noStore(response);
        return ApiResponse.success(previewService.catalog(
                currentUserId(httpRequest), sourceCode, page, categoryId));
    }

    @AdminOnly
    @GetMapping("/{sourceCode}/detail")
    public ApiResponse<ExternalVideoSourceDetailVO> detail(
            @PathVariable("sourceCode") String sourceCode,
            @RequestParam("workId") String workId,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        noStore(response);
        return ApiResponse.success(previewService.detail(
                currentUserId(httpRequest), sourceCode, workId));
    }

    /**
     * 管理端预览响应不允许被任何缓存保存：在调用服务层<b>之前</b>写 header。
     *
     * <p>提前写是刻意的：服务层抛 {@code BusinessException}（例如 503）时，全局异常处理器只决定状态码与
     * 响应体，不会清掉已经写下的 header，因此那条拒绝响应同样带 {@code no-store}。</p>
     */
    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", CACHE_CONTROL_NO_STORE);
    }

    /**
     * 只读登录态里的 uid：请求参数、请求体、header 都不参与。
     *
     * <p>返回 {@code null} 时由服务层按 401 拒绝，不在这一层伪造一个"匿名管理员"身份。</p>
     */
    private static Integer currentUserId(HttpServletRequest request) {
        return (Integer) request.getAttribute("currentUserId");
    }
}
