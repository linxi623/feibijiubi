package com.feibijiubi.backend.videosource.controller;

import com.feibijiubi.backend.auth.annotation.OptionalLogin;
import com.feibijiubi.backend.shared.ApiResponse;
import com.feibijiubi.backend.videosource.service.VideoPlaybackService;
import com.feibijiubi.backend.videosource.vo.VideoEpisodePageVO;
import com.feibijiubi.backend.videosource.vo.VideoPlaybackVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * M6-A 公开只读播放/选集。
 *
 * <p>{@code @OptionalLogin}：未登录可读公开内容；登录后本人可读自己的私密剧集。管理员在公共侧没有
 * 额外绕过（与服务层一致）。两个 GET 都在服务调用前设置 {@code Cache-Control: no-store}：播放地址是
 * 逐次授予、可能变化的上游凭据，不能被浏览器或中间代理缓存。GET 纯读，不联网、不计播放、不写 Redis/MySQL。</p>
 */
@RestController
@RequestMapping("/api/videos/{vid}")
@RequiredArgsConstructor
public class VideoPlaybackController {

    private final VideoPlaybackService playbackService;

    @OptionalLogin
    @GetMapping("/playback")
    public ApiResponse<VideoPlaybackVO> playback(HttpServletRequest request,
                                                 @PathVariable("vid") Integer vid,
                                                 @RequestParam(value = "lineId", required = false) String lineId,
                                                 HttpServletResponse response) {
        noStore(response);
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        return ApiResponse.success(playbackService.playback(currentUserId, vid, lineId));
    }

    @OptionalLogin
    @GetMapping("/episodes")
    public ApiResponse<VideoEpisodePageVO> episodes(HttpServletRequest request,
                                                    @PathVariable("vid") Integer vid,
                                                    @RequestParam(value = "cursor", required = false) String cursor,
                                                    @RequestParam(value = "size", required = false) String size,
                                                    HttpServletResponse response) {
        noStore(response);
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        return ApiResponse.success(playbackService.episodes(currentUserId, vid, cursor, size));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }
}
