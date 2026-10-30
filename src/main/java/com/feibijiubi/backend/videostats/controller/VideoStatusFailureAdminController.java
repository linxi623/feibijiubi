package com.feibijiubi.backend.videostats.controller;

import com.feibijiubi.backend.auth.annotation.AdminOnly;
import com.feibijiubi.backend.shared.ApiResponse;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.videostats.dto.VideoStatusFailureAcknowledgeDTO;
import com.feibijiubi.backend.videostats.dto.VideoStatusStatus3ReplayDTO;
import com.feibijiubi.backend.videostats.service.admin.VideoStatusFailureAdminService;
import com.feibijiubi.backend.videostats.service.admin.VideoStatusStatus3ReplayService;
import com.feibijiubi.backend.videostats.vo.VideoStatusStatus3ReplayVO;
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
 * T10 管理员失败处置接口：分页、详情、预检查、受审计确认（ACKNOWLEDGE）与 T10-B1
 * consumed status3 受控重放。
 *
 * <p>安全边界：操作人只取登录态 {@code currentUserId}（请求体不可伪造）；响应不返回
 * payload/leaseToken/last_error 原文；所有方法都在 {@code /api/admin/**} 下且标注
 * {@link AdminOnly}。重放的投递交给既有 Outbox relay，接口本身不在事务内发送消息。</p>
 */
@RestController
@RequestMapping("/api/admin/video-status/failures")
@RequiredArgsConstructor
public class VideoStatusFailureAdminController {

    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 100;

    private final VideoStatusFailureAdminService failureAdminService;
    private final VideoStatusStatus3ReplayService status3ReplayService;

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

    /**
     * T10-B1：consumed status3 单事件 proven-only 受控重放。
     *
     * <p>只接受 {@code source=CONSUMED}；Outbox FAILED 的重放是另一条接口。目标 eventId 只能
     * 来自路径，事件内容只能来自数据库，请求体只有 operationId/reason。</p>
     */
    @AdminOnly
    @PostMapping("/{eventId}/replay")
    public ApiResponse<VideoStatusStatus3ReplayVO> replayStatus3(
            @PathVariable("eventId") String eventId,
            @RequestParam("source") String source,
            @Valid @RequestBody VideoStatusStatus3ReplayDTO request,
            HttpServletRequest httpRequest) {
        Integer currentUserId = (Integer) httpRequest.getAttribute("currentUserId");
        if (currentUserId == null) {
            throw new BusinessException(401,
                    "未登录：操作人必须来自登录态，不能由请求体伪造");
        }
        VideoStatusStatus3ReplayVO vo = status3ReplayService.replay(
                source, eventId, request.getOperationId(), request.getReason(),
                String.valueOf(currentUserId));
        return ApiResponse.success(
                vo.isDuplicate()
                        ? "operationId 已完成，未重复执行重放"
                        : "已重置为 RECEIVED，等待 Outbox relay 投递",
                vo);
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