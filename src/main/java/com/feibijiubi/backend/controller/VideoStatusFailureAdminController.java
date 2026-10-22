package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.dto.VideoStatusFailureAcknowledgeDTO;
import com.feibijiubi.backend.service.admin.VideoStatusFailureAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T10 第一阶段管理员接口：失败事件分页、详情、预检查与受审计确认（ACKNOWLEDGE）。
 * 确认只写审计，不修改事件/计数/Redis；不返回 payload/leaseToken/last_error 原文，
 * 不提供 status3 重放。
 */
@RestController
@RequestMapping("/api/admin/video-status/failures")
@RequiredArgsConstructor
public class VideoStatusFailureAdminController {

    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 100;

    private final VideoStatusFailureAdminService failureAdminService;

    @AdminOnly
    @GetMapping
    public ApiResponse<VideoStatusFailureAdminService.Page> list(
            @RequestParam(value = "source", defaultValue = "OUTBOX") String source,
            @RequestParam(value = "afterId", defaultValue = "0") long afterId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        requirePaging(afterId, limit);
        return ApiResponse.success(failureAdminService.list(source, afterId, limit));
    }

    @AdminOnly
    @GetMapping("/{eventId}")
    public ApiResponse<VideoStatusFailureAdminService.Detail> detail(
            @PathVariable("eventId") String eventId,
            @RequestParam("source") String source) {
        return ApiResponse.success(failureAdminService.detail(source, eventId));
    }

    @AdminOnly
    @GetMapping("/{eventId}/precheck")
    public ApiResponse<VideoStatusFailureAdminService.Precheck> precheck(
            @PathVariable("eventId") String eventId,
            @RequestParam("source") String source) {
        return ApiResponse.success(failureAdminService.precheck(source, eventId));
    }

    @AdminOnly
    @PostMapping("/{eventId}/acknowledge")
    public ApiResponse<VideoStatusFailureAdminService.AcknowledgeResult> acknowledge(
            @PathVariable("eventId") String eventId,
            @RequestParam("source") String source,
            @Valid @RequestBody VideoStatusFailureAcknowledgeDTO request,
            HttpServletRequest httpRequest) {
        Integer currentUserId = (Integer) httpRequest.getAttribute("currentUserId");
        if (currentUserId == null) {
            throw new BusinessException(401,
                    "未登录：操作人必须来自登录态，不能由请求体伪造");
        }
        VideoStatusFailureAdminService.AcknowledgeResult result =
                failureAdminService.acknowledge(source, eventId, request.getOperationId(),
                        request.getReason(), String.valueOf(currentUserId));
        return ApiResponse.success(
                result.duplicate() ? "operationId 已完成，未重复写审计" : "确认已阅已审计",
                result);
    }

    private static void requirePaging(long afterId, int limit) {
        if (afterId < 0L) {
            throw new BusinessException(400, "afterId 必须 >= 0");
        }
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BusinessException(400,
                    "limit 必须在 " + MIN_LIMIT + " 到 " + MAX_LIMIT + " 之间");
        }
    }
}