package com.feibijiubi.backend.interaction.controller;

import com.feibijiubi.backend.auth.annotation.OptionalLogin;
import com.feibijiubi.backend.shared.ApiResponse;
import com.feibijiubi.backend.interaction.service.UserVideoService;
import com.feibijiubi.backend.interaction.vo.VideoInteractionVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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

    /**
     * 播放计数：需要 {@code Idempotency-Key}；未登录时还需要 {@code X-Visitor-Id}。
     *
     * <p>两个头都用 {@code required = false} 接收，再由服务层给出明确的 400：如果交给 Spring 的
     * 必填校验，缺头会抛 {@code MissingRequestHeaderException}，全局异常处理器目前只覆盖了
     * 参数缺失/类型不符两个分支，会退化成 500，把“客户端忘了带头”误报成服务端错误。</p>
     */
    @OptionalLogin
    @PostMapping("/{vid}/play-count")
    public ApiResponse<VideoInteractionVO> increasePlayCount(
            @PathVariable Integer vid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Visitor-Id", required = false) String visitorId,
            HttpServletRequest request) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        return ApiResponse.success(userVideoService.increasePlayCount(
                currentUserId, vid, idempotencyKey, visitorId));
    }

    @PostMapping("/{vid}/progress")
    public ApiResponse<Void> savePlayProgress(@PathVariable Integer vid,
                                              HttpServletRequest request,
                                              @RequestParam("playTime") Double playTime) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        userVideoService.savePlayProgress(vid, currentUserId, playTime);
        return ApiResponse.success(null);
    }

    @PostMapping("/{vid}/islike")
    public ApiResponse<Void> recordLike(@PathVariable Integer vid,
                                        HttpServletRequest request,
                                        @RequestParam("islike")Boolean islike,
                                        @RequestParam("isSet") Boolean isSet) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        userVideoService.recordLike(currentUserId, vid, islike, isSet);
        return ApiResponse.success(null);
    }

    @PostMapping("/{vid}/coin")
    public ApiResponse<Void> increaseCoin(@PathVariable Integer vid,
                                          HttpServletRequest request,
                                          @RequestParam("coin")Byte coin) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        userVideoService.increaseCoin(currentUserId, vid, coin);
        return ApiResponse.success(null);
    }

    @OptionalLogin
    @PostMapping("/{vid}/share")
    public ApiResponse<VideoInteractionVO> increaseShare(
            @PathVariable Integer vid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Visitor-Id", required = false) String visitorId,
            HttpServletRequest request) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        return ApiResponse.success(userVideoService.increaseShare(
                currentUserId, vid, idempotencyKey, visitorId));
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
