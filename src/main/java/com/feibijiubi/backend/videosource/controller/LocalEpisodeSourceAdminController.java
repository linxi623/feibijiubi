package com.feibijiubi.backend.videosource.controller;

import com.feibijiubi.backend.auth.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.videosource.dto.LocalEpisodeSourceCaptureDTO;
import com.feibijiubi.backend.videosource.dto.LocalEpisodeSourceConfirmDTO;
import com.feibijiubi.backend.videosource.service.LocalEpisodeSourceService;
import com.feibijiubi.backend.videosource.vo.LocalEpisodeSourceVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.List;

@RestController
@RequestMapping("/api/admin/local-video-episodes/{episodeId}/sources")
@RequiredArgsConstructor
public class LocalEpisodeSourceAdminController {
    private final LocalEpisodeSourceService service;

    @AdminOnly
    @GetMapping
    public ApiResponse<LocalEpisodeSourceVO> current(@PathVariable("episodeId") long episodeId,
                                                     HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.current(episodeId));
    }

    @AdminOnly
    @GetMapping("/candidates")
    public ApiResponse<List<LocalEpisodeSourceVO.Candidate>> candidates(@PathVariable("episodeId") long episodeId,
            @RequestParam(value = "afterId", defaultValue = "0") long afterId,
            @RequestParam(value = "limit", defaultValue = "20") int limit, HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.candidates(episodeId, afterId, limit));
    }

    @AdminOnly
    @PostMapping("/candidates")
    public ApiResponse<LocalEpisodeSourceVO.Candidate> capture(@PathVariable("episodeId") long episodeId,
            @RequestBody LocalEpisodeSourceCaptureDTO body, HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.capture((Integer) request.getAttribute("currentUserId"), episodeId, body));
    }

    @AdminOnly
    @PostMapping("/candidates/{candidateId}/confirm")
    public ApiResponse<LocalEpisodeSourceVO> confirm(@PathVariable("episodeId") long episodeId,
            @PathVariable("candidateId") long candidateId, @RequestBody LocalEpisodeSourceConfirmDTO body,
            HttpServletRequest request, HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.confirm((Integer) request.getAttribute("currentUserId"), episodeId, candidateId, body));
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
