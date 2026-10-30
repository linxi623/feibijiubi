package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.auth.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusStatus0CompensationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T09 status0 补偿只读诊断接口（管理员）。
 *
 * <p>安全边界：</p>
 * <ul>
 *   <li>路径在 {@code /api/admin/**} 下，由既有 {@code LoginInterceptor} +
 *       {@code AdminInterceptor} 拦截，并标注 {@link AdminOnly}；</li>
 *   <li>只有一个 GET，直接调用服务层 {@code preview}，不领取租约、不发送 MQ、
 *       不写 Redis/DB；默认总开关关闭时仍可访问，这是唯一可用的运维路径；</li>
 *   <li>不提供运行期 pause/启停写接口，也不接配置中心；启停仍由 yaml + 重启控制。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/video-status/status0-compensation")
@RequiredArgsConstructor
public class VideoStatusStatus0CompensationController {

    static final int MIN_PREVIEW_LIMIT = 1;
    static final int MAX_PREVIEW_LIMIT = 100;

    private final VideoStatusStatus0CompensationService compensationService;

    @AdminOnly
    @GetMapping("/preview")
    public ApiResponse<VideoStatusStatus0CompensationService.PreviewResult> preview(
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @RequestParam(value = "afterId", defaultValue = "0") long afterId
    ) {
        if (limit < MIN_PREVIEW_LIMIT || limit > MAX_PREVIEW_LIMIT) {
            throw new BusinessException(400,
                    "limit 必须在 " + MIN_PREVIEW_LIMIT + " 到 "
                            + MAX_PREVIEW_LIMIT + " 之间");
        }
        if (afterId < 0L) {
            throw new BusinessException(400, "afterId 必须 >= 0");
        }
        return ApiResponse.success(
                compensationService.preview(limit, afterId));
    }
}
