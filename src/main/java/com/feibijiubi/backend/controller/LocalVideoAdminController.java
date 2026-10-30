package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.dto.LocalVideoEpisodeCreateDTO;
import com.feibijiubi.backend.dto.LocalVideoSeriesCreateDTO;
import com.feibijiubi.backend.service.localvideo.LocalVideoCatalogService;
import com.feibijiubi.backend.vo.LocalVideoEpisodeVO;
import com.feibijiubi.backend.vo.LocalVideoPageVO;
import com.feibijiubi.backend.vo.LocalVideoSeriesVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S1：管理员创建/查询独立本地作品与剧集的四个接口。
 *
 * <ul>
 *   <li>{@code POST /api/admin/local-video-series}：创建本地作品；</li>
 *   <li>{@code GET  /api/admin/local-video-series?afterId=0&limit=20}：keyset 分页查询；</li>
 *   <li>{@code POST /api/admin/local-video-series/{seriesId}/episodes}：在既有作品下创建剧集；</li>
 *   <li>{@code GET  /api/admin/local-video-series/{seriesId}/episodes?afterId=0&limit=20}：分页查询剧集。</li>
 * </ul>
 *
 * <h2>鉴权与操作人</h2>
 * <p>方法都在 {@code /api/admin/**} 下，由既有的 {@code LoginInterceptor}（401）与
 * {@code AdminInterceptor}（403）先行拦截，并标注 {@link AdminOnly} 与既有约定一致。
 * 创建者只取登录态 {@code currentUserId}，<b>没有</b>任何 uid 查询参数或请求体字段；
 * 该属性缺失时服务层按 401 拒绝（不在这一层伪造身份）。</p>
 *
 * <h2>写请求约定</h2>
 * <p>全部写操作用 {@code POST}（与仓库既有惯例一致，没有 PUT/DELETE）。没有 update / delete /
 * 导入 / 合并接口，也没有 operationId：本片的“创建”不是幂等创建，同标题可以出现多个空草稿，
 * 但不会因此产生第二个 video（本片根本不建 video）。</p>
 *
 * <h2>错误码</h2>
 * <p>参数与错误码沿用全局异常处理器：{@code BusinessException.code} 即 HTTP 状态，因此
 * 400（非法 id / 分页越界 / 空白或超长标题）、404（父作品不存在）、401、500（写入异常）
 * 都体现在状态码上。数据库异常<b>不</b>统一翻译成 409：本片没有版本冲突语义。</p>
 *
 * <h2>响应不被缓存</h2>
 * <p>四个方法都在<b>调用服务层之前</b>写 {@code Cache-Control: no-store}，所以成功响应与服务层自己抛出的
 * 业务错误（400/404/500）都带这条 header。边界同既有来源预览接口：401/403 由拦截器在本类执行前结束请求，
 * 那时还没有 controller 代码跑过，因此<b>不能</b>声称“所有错误响应都带 no-store”。</p>
 */
@RestController
@RequestMapping("/api/admin/local-video-series")
@RequiredArgsConstructor
public class LocalVideoAdminController {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody() {
        return ResponseEntity.badRequest()
                .header("Cache-Control", "no-store")
                .body(ApiResponse.fail(400, "请求体必须是合法 JSON"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedContentType() {
        return ResponseEntity.status(415)
                .header("Cache-Control", "no-store")
                .body(ApiResponse.fail(415, "请求内容类型不受支持，请使用 application/json"));
    }

    private static final String CACHE_CONTROL_NO_STORE = "no-store";
    private static final String DEFAULT_LIMIT = "20";

    private final LocalVideoCatalogService catalogService;

    @AdminOnly
    @PostMapping
    public ApiResponse<LocalVideoSeriesVO> createSeries(
            @Valid @RequestBody LocalVideoSeriesCreateDTO request,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        noStore(response);
        return ApiResponse.success("本地作品已创建",
                catalogService.createSeries(currentUserId(httpRequest), request.getTitle()));
    }

    @AdminOnly
    @GetMapping
    public ApiResponse<LocalVideoPageVO<LocalVideoSeriesVO>> listSeries(
            @RequestParam(value = "afterId", defaultValue = "0") long afterId,
            @RequestParam(value = "limit", defaultValue = DEFAULT_LIMIT) int limit,
            HttpServletResponse response
    ) {
        noStore(response);
        requirePaging(afterId, limit);
        return ApiResponse.success(catalogService.listSeries(afterId, limit));
    }

    @AdminOnly
    @PostMapping("/{seriesId}/episodes")
    public ApiResponse<LocalVideoEpisodeVO> createEpisode(
            @PathVariable("seriesId") long seriesId,
            @Valid @RequestBody LocalVideoEpisodeCreateDTO request,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        noStore(response);
        return ApiResponse.success("本地剧集已创建",
                catalogService.createEpisode(currentUserId(httpRequest), seriesId,
                        request.getDisplayTitle()));
    }

    @AdminOnly
    @GetMapping("/{seriesId}/episodes")
    public ApiResponse<LocalVideoPageVO<LocalVideoEpisodeVO>> listEpisodes(
            @PathVariable("seriesId") long seriesId,
            @RequestParam(value = "afterId", defaultValue = "0") long afterId,
            @RequestParam(value = "limit", defaultValue = DEFAULT_LIMIT) int limit,
            HttpServletResponse response
    ) {
        noStore(response);
        requirePaging(afterId, limit);
        return ApiResponse.success(catalogService.listEpisodes(seriesId, afterId, limit));
    }

    /**
     * 分页参数在 controller 也校验一次：这样“请求根本没到服务层”的越界输入也能稳定得到 400
     * （与既有管理列表一致）。服务层保留同一套校验，直接调用服务同样安全。
     */
    private static void requirePaging(long afterId, int limit) {
        if (afterId < 0L) {
            throw new BusinessException(400, "afterId 必须 >= 0");
        }
        if (limit < LocalVideoCatalogService.MIN_PAGE_LIMIT
                || limit > LocalVideoCatalogService.MAX_PAGE_LIMIT) {
            throw new BusinessException(400,
                    "limit 必须在 " + LocalVideoCatalogService.MIN_PAGE_LIMIT + " 到 "
                            + LocalVideoCatalogService.MAX_PAGE_LIMIT + " 之间");
        }
    }

    /** 在调用服务层之前写 header：服务层抛业务异常时这条 header 仍然保留。 */
    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", CACHE_CONTROL_NO_STORE);
    }

    /** 只读登录态里的 uid：查询参数、请求体、header 都不参与；{@code null} 交给服务层判 401。 */
    private static Integer currentUserId(HttpServletRequest request) {
        return (Integer) request.getAttribute("currentUserId");
    }
}
