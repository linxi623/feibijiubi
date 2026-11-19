package com.feibijiubi.backend.videosource.controller;

import com.feibijiubi.backend.auth.annotation.AdminOnly;
import com.feibijiubi.backend.shared.ApiResponse;
import com.feibijiubi.backend.videosource.dto.LocalEpisodeImportDTO;
import com.feibijiubi.backend.videosource.service.LocalEpisodeImportService;
import com.feibijiubi.backend.videosource.vo.LocalEpisodeImportVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M5：管理员把本地剧集导入为 video 的唯一写入口。
 *
 * <p>操作者只来自登录态 {@code currentUserId}；请求体没有 uid / vid / sourceType / status /
 * duration / videoUrl / videoKey。成功响应与服务层业务错误都在调用服务前设置
 * {@code Cache-Control: no-store}；拦截器提前返回的 401/403 不在此保证内。</p>
 */
@RestController
@RequestMapping("/api/admin/local-video-episodes")
@RequiredArgsConstructor
public class LocalEpisodeImportAdminController {

    private final LocalEpisodeImportService service;

    @AdminOnly
    @PostMapping("/{episodeId}/import")
    public ApiResponse<LocalEpisodeImportVO> importEpisode(@PathVariable("episodeId") long episodeId,
            @RequestBody LocalEpisodeImportDTO body, HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.importEpisode(
                (Integer) request.getAttribute("currentUserId"), episodeId, body));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody() {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store")
                .body(ApiResponse.fail(400, "请求体必须是合法JSON"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedContentType() {
        return ResponseEntity.status(415).header("Cache-Control", "no-store")
                .body(ApiResponse.fail(415, "请使用application/json"));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }
}
