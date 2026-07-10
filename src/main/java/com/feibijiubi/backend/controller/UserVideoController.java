package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.annotation.OptionalLogin;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.service.video.UserVideoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/videos")
public class UserVideoController {
    private final UserVideoService userVideoService;

    public UserVideoController(UserVideoService userVideoService) {
        this.userVideoService = userVideoService;
    }

    @OptionalLogin
    @PostMapping("/{vid}/play-count")
    public ApiResponse<Void> increasePlayCount(@PathVariable Integer vid) {
        userVideoService.increasePlayCount(vid);
        return ApiResponse.success(null);
    }

    @PutMapping("/{vid}/progress")
    public ApiResponse<Void> savePlayProgress(@PathVariable Integer vid,
                                              HttpServletRequest request,
                                              @RequestParam("playTime") Double playTime) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        userVideoService.savePlayProgress(vid, currentUserId, playTime);
        return ApiResponse.success(null);
    }

    @PutMapping("/{vid}/islike")
    public ApiResponse<Void> recordLike(@PathVariable Integer vid,
                                        HttpServletRequest request,
                                        @RequestParam("islike")Boolean islike,
                                        @RequestParam("isSet") Boolean isSet) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        userVideoService.recordLike(currentUserId, vid, islike, isSet);
        return ApiResponse.success(null);
    }

    @PutMapping("/{vid}/coin")
    public ApiResponse<Void> increaseCoin(@PathVariable Integer vid,
                                          HttpServletRequest request,
                                          @RequestParam("coin")Byte coin) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        userVideoService.increaseCoin(currentUserId, vid, coin);
        return ApiResponse.success(null);
    }

    @OptionalLogin
    @PutMapping("/{vid}/share")
    public ApiResponse<Void> increaseShare(@PathVariable Integer vid) {
        userVideoService.increaseShare(vid);
        return ApiResponse.success(null);
    }

    @PostMapping("/{vid}/collect")
    public ApiResponse<Void> Collect(@PathVariable Integer vid,
                                             HttpServletRequest request,
                                             @RequestParam("isCollect")Boolean isCollect) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        userVideoService.Collect(currentUserId, vid, isCollect);
        return ApiResponse.success(null);
    }

}
