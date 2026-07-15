package com.feibijiubi.backend.controller;


import com.feibijiubi.backend.annotation.OptionalLogin;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.dto.VideoSubmitDTO;
import com.feibijiubi.backend.dto.VideoUploadPrepareDTO;
import com.feibijiubi.backend.service.ratelimit.RateLimitService;
import com.feibijiubi.backend.service.storage.FileStorageService;
import com.feibijiubi.backend.service.video.VideoService;
import com.feibijiubi.backend.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoController {
    private final FileStorageService fileStorageService;
    private final VideoService videoService;
    private final RateLimitService rateLimitService;

    @PostMapping("/upload-url")
    public ApiResponse<VideoUploadPrepareVO> uploadUrl(HttpServletRequest httprequest,
                                                       @Valid @RequestBody VideoUploadPrepareDTO request) {
        Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
        rateLimitService.checkUploadTokenLimit(currentUserId);
        VideoUploadPrepareVO vo = fileStorageService.uploadPrepare(currentUserId, request);
        return ApiResponse.success(vo);
    }

    @PostMapping("/cover")
    public ApiResponse<String> uploadCover(HttpServletRequest request,
                                           @RequestParam("file") MultipartFile file) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        String objectKey = videoService.uploadCover(currentUserId, file);
        return ApiResponse.success(objectKey);
    }

    @PostMapping()
    public ApiResponse<VideoSubmitVO> submitVideo(HttpServletRequest httprequest,
                                                  @Valid @RequestBody VideoSubmitDTO request) {
        Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
        VideoSubmitVO vo = videoService.submitVideo(currentUserId, request);
        return ApiResponse.success("投稿成功", vo);
    }

    @PostMapping("/{vid}/delete")
    public ApiResponse<Void> deleteVideo(HttpServletRequest httprequest, @PathVariable Integer vid) {
        Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
        videoService.deleteVideo(currentUserId, vid);
        return ApiResponse.successMessage("删除成功");
    }

    @OptionalLogin
    @GetMapping("/{vid}")
    public ApiResponse<VideoDetailVO> getVideoDetail(HttpServletRequest httprequest,
                                                     @PathVariable Integer vid) {
        Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
        VideoDetailVO vo = videoService.getVideoDetail(currentUserId, vid);
        return ApiResponse.success(vo);
    }

    @OptionalLogin
    @GetMapping("/feed")
    public ApiResponse<CursorPageVO<VideoListItemVO>> getVideoFeed(@RequestParam(required = false) String cursor,
                                                  @RequestParam(defaultValue = "15") Integer size) {
        CursorPageVO<VideoListItemVO> vo = videoService.getVideoFeed(cursor, size);
        return ApiResponse.success(vo);
    }
}
