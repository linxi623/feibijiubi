package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.auth.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusReconciliationService;
import com.feibijiubi.backend.vo.VideoStatusReconciliationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T10 单视频只读事件对账接口（管理员）。
 *
 * <p>安全边界：</p>
 * <ul>
 *   <li>路径在既有 {@code /api/admin/**} 下，并标注 {@link AdminOnly}；</li>
 *   <li>只有一个 GET，不提供修复/重放/写入入口；</li>
 *   <li>服务层所有查询有界且只读，响应不含 payload/错误原文/token，
 *       并明确返回并发非原子与截断/未知标记，不返回 SAFE。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/video-status")
@RequiredArgsConstructor
public class VideoStatusReconciliationController {

    private final VideoStatusReconciliationService reconciliationService;

    @AdminOnly
    @GetMapping("/reconciliation")
    public ApiResponse<VideoStatusReconciliationVO> reconcile(
            @RequestParam("vid") int vid,
            @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        if (vid <= 0) {
            throw new BusinessException(400, "vid 必须是正整数");
        }
        if (limit < VideoStatusReconciliationService.MIN_LIMIT
                || limit > VideoStatusReconciliationService.MAX_LIMIT) {
            throw new BusinessException(400,
                    "limit 必须在 " + VideoStatusReconciliationService.MIN_LIMIT
                            + " 到 " + VideoStatusReconciliationService.MAX_LIMIT
                            + " 之间");
        }
        return ApiResponse.success(reconciliationService.reconcile(vid, limit));
    }
}
