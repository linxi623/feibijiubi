package com.feibijiubi.backend.controller;


import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.dto.VideoSubmitDTO;
import com.feibijiubi.backend.dto.VideoUploadPrepareDTO;
import com.feibijiubi.backend.service.storage.FileStorageService;
import com.feibijiubi.backend.service.video.VideoService;
import com.feibijiubi.backend.vo.VideoSubmitVO;
import com.feibijiubi.backend.vo.VideoUploadPrepareVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/videos")
public class VideoController {
    private final FileStorageService fileStorageService;
    private final VideoService videoService;

    public VideoController(FileStorageService fileStorageService,
                           VideoService videoService) {
        this.fileStorageService = fileStorageService;
        this.videoService = videoService;
    }

    @PostMapping("/upload-url")
    public ApiResponse<VideoUploadPrepareVO> uploadUrl(HttpServletRequest httprequest,
                                                       @RequestBody VideoUploadPrepareDTO request) {
        Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
        VideoUploadPrepareVO vo = fileStorageService.uploadPrepare(currentUserId, request);
        return ApiResponse.success(vo);
    }

    @PostMapping("/cover")
    public ApiResponse<String> uploadCover(HttpServletRequest request,
                                           @RequestParam("file") MultipartFile file) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        String url = videoService.uploadCover(currentUserId, file);
        return ApiResponse.success(url);
    }

    @PostMapping
    public ApiResponse<VideoSubmitVO> submitVideo(HttpServletRequest httprequest,
                                                  @RequestBody VideoSubmitDTO request) {
        Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
        VideoSubmitVO vo = videoService.submitVideo(currentUserId, request);
        return ApiResponse.success("投稿成功", vo);
    }
}
