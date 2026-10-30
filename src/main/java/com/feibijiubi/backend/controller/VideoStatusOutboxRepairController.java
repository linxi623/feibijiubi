package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.auth.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.dto.VideoStatusOutboxReplayDTO;
import com.feibijiubi.backend.service.admin.VideoStatusOutboxReplayService;
import com.feibijiubi.backend.vo.VideoStatusOutboxReplayVO;
import com.feibijiubi.backend.vo.VideoStatusOutboxStatusVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T07 临时运维接口（T10 极窄子集）：单个 Outbox FAILED 事件的查询与受审计重放。
 *
 * <p>安全边界：</p>
 * <ul>
 *   <li>路径在 {@code /api/admin/**}，由既有 {@code LoginInterceptor} + {@code AdminInterceptor}
 *       拦截，且每个方法都标 {@link AdminOnly}；</li>
 *   <li>GET 只返回安全字段，不返回 payload、leaseToken 与 last_error 原文
 *       （历史值可能含 payload/凭证）；</li>
 *   <li>POST 的操作人只取 {@code currentUserId}，DTO 里<b>没有</b> operator，无法伪造；</li>
 *   <li>不新增 actuator / 未鉴权端口；错误统一走 {@link BusinessException} →
 *       {@code GlobalExceptionHandler}（400/404/409/422）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/video-status/outbox")
@RequiredArgsConstructor
public class VideoStatusOutboxRepairController {

    private final VideoStatusOutboxReplayService replayService;

    /** 只读查询：安全状态/计数/时间，不含 payload、leaseToken 与 last_error 原文。 */
    @AdminOnly
    @GetMapping("/{eventId}")
    public ApiResponse<VideoStatusOutboxStatusVO> getStatus(@PathVariable("eventId") String eventId) {
        return ApiResponse.success(replayService.getStatus(eventId));
    }

    /**
     * 受审计重放：FAILED → PENDING（同事务写 COMPLETED 审计）。
     *
     * <p>并发冲突（同一 operationId 同时写入）由唯一键拦下，事务整体回滚；
     * 这里把它翻译成明确的 409，而不是让请求看起来成功。</p>
     */
    @AdminOnly
    @PostMapping("/{eventId}/replay")
    public ApiResponse<VideoStatusOutboxReplayVO> replay(
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody VideoStatusOutboxReplayDTO request,
            HttpServletRequest httpRequest) {
        Integer currentUserId = (Integer) httpRequest.getAttribute("currentUserId");
        if (currentUserId == null) {
            throw new BusinessException(401, "未登录：操作人必须来自登录态，不能由请求体伪造");
        }
        try {
            VideoStatusOutboxReplayVO vo = replayService.replay(
                    eventId,
                    request.getOperationId(),
                    request.getReason(),
                    String.valueOf(currentUserId)
            );
            return ApiResponse.success(vo.isDuplicate() ? "operationId 已完成，未重复执行" : "重放已提交", vo);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409,
                    "并发冲突：operationId 同时被另一处置请求写入（唯一键拒绝），本次事务已回滚，未重复执行；"
                            + "请用 GET 查询状态后决定是否换 operationId");
        }
    }
}
