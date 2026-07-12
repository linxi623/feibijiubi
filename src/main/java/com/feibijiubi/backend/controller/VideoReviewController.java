package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.dto.VideoReviewDTO;
import com.feibijiubi.backend.service.video.VideoReviewService;
import com.feibijiubi.backend.vo.AdminVideoDetailVO;
import com.feibijiubi.backend.vo.AdminVideoListItemVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/videos")
public class VideoReviewController {

    private final VideoReviewService videoReviewService;

    public VideoReviewController(VideoReviewService videoReviewService) {
        this.videoReviewService = videoReviewService;
    }

    @AdminOnly
    @PutMapping("/{vid}/review")
    public ApiResponse<Void> videoReview(HttpServletRequest httprequest,
                                         @PathVariable Integer vid,
                                         @Valid @RequestBody VideoReviewDTO request) {
        Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
        videoReviewService.videoReview(currentUserId, vid, request);
        return ApiResponse.success(null);
    }

    @AdminOnly
    @GetMapping("/{vid}")
    public ApiResponse<AdminVideoDetailVO> getVideo(@PathVariable Integer vid,
                                                    HttpServletRequest request) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        AdminVideoDetailVO detail = videoReviewService.getVideo(currentUserId, vid);
        return ApiResponse.success(detail);
    }

    @AdminOnly
    @GetMapping("/page")
    public ApiResponse<List<AdminVideoListItemVO>> getVideoPage(
            @RequestParam("page") Integer page,
            @RequestParam(value = "status", defaultValue = "1") Byte status,
            @RequestParam(value = "quantity", defaultValue = "10") Integer quantity) {
        List<AdminVideoListItemVO> items = videoReviewService.getVideoList(page, status, quantity);
        return ApiResponse.success(items);
    }
}
