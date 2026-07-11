package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.dto.VideoReviewDTO;
import com.feibijiubi.backend.service.video.VideoReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

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

}
