package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.auth.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.dto.VideoStatusOutboxReplayDTO;
import com.feibijiubi.backend.service.admin.VideoStatusOutboxReplayService;
import com.feibijiubi.backend.vo.VideoStatusOutboxReplayVO;
import com.feibijiubi.backend.vo.VideoStatusOutboxStatusVO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T07 临时运维接口的纯 mock 约束测试（不起 Spring 容器、不连任何数据库）。
 *
 * <p>覆盖：管理员映射与 {@link AdminOnly}、GET/POST 路径、DTO 参数边界、
 * 操作人只来自登录态、以及“并发唯一键冲突 → 明确 409”的翻译。</p>
 */
class VideoStatusOutboxRepairAuthorizationTests {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @AfterAll
    static void closeValidator() {
        Validation.buildDefaultValidatorFactory().close();
    }

    @Test
    void controllerIsMappedUnderExistingAdminApiWithAdminOnlyAnnotations() {
        Class<VideoStatusOutboxRepairController> type = VideoStatusOutboxRepairController.class;
        assertNotNull(type.getAnnotation(RestController.class));
        RequestMapping classMapping = type.getAnnotation(RequestMapping.class);
        assertNotNull(classMapping);
        assertEquals("/api/admin/video-status/outbox", classMapping.value()[0],
                "必须在既有 /api/admin/** 鉴权链路上");

        for (String methodName : List.of("getStatus", "replay")) {
            Method method = Arrays.stream(type.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            assertNotNull(method.getAnnotation(AdminOnly.class), methodName + " 必须标注 @AdminOnly");
            assertEquals(ApiResponse.class, method.getReturnType(), "错误/成功都用统一 ApiResponse");
        }

        List<String> reads = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getAnnotation(GetMapping.class) != null)
                .flatMap(method -> Arrays.stream(method.getAnnotation(GetMapping.class).value()))
                .toList();
        assertEquals(List.of("/{eventId}"), reads, "GET 只能按 eventId 查询单个事件");

        List<String> writes = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getAnnotation(PostMapping.class) != null)
                .flatMap(method -> Arrays.stream(method.getAnnotation(PostMapping.class).value()))
                .toList();
        assertEquals(List.of("/{eventId}/replay"), writes, "只允许一个极窄的 POST，不用 PUT/DELETE");
        assertTrue(reads.stream().noneMatch(path -> path.startsWith("/actuator")));
    }

    @Test
    void dtoCannotForgeOperatorAndRequiresOperationIdAndReason() throws Exception {
        for (Field field : VideoStatusOutboxReplayDTO.class.getDeclaredFields()) {
            assertFalse(field.getName().equalsIgnoreCase("operator"),
                    "DTO 不允许 operator：操作人只能来自登录态 currentUserId");
        }

        VideoStatusOutboxReplayDTO blank = new VideoStatusOutboxReplayDTO();
        Set<ConstraintViolation<VideoStatusOutboxReplayDTO>> violations = validator.validate(blank);
        assertEquals(2, violations.size(), "operationId 与 reason 都必须非空: " + violations);
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("operationId")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("reason")));

        VideoStatusOutboxReplayDTO tooLong = new VideoStatusOutboxReplayDTO();
        tooLong.setOperationId("o".repeat(65));
        tooLong.setReason("r");
        Set<ConstraintViolation<VideoStatusOutboxReplayDTO>> lengthViolations =
                validator.validate(tooLong);
        assertEquals(1, lengthViolations.size());
        assertEquals("operationId", lengthViolations.iterator().next().getPropertyPath().toString());

        VideoStatusOutboxReplayDTO ok = new VideoStatusOutboxReplayDTO();
        ok.setOperationId("o".repeat(64));
        ok.setReason("r");
        assertTrue(validator.validate(ok).isEmpty(), "64 字符 operationId 必须通过");
    }

    @Test
    void replayRequiresLoginAndUsesCurrentUserIdAsOperator() {
        VideoStatusOutboxReplayService service = mock(VideoStatusOutboxReplayService.class);
        VideoStatusOutboxRepairController controller = new VideoStatusOutboxRepairController(service);
        VideoStatusOutboxReplayDTO request = request("op-1", "恢复发布");

        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        BusinessException unauthenticated = assertThrows(BusinessException.class,
                () -> controller.replay("evt-1", request, anonymous));
        assertEquals(401, unauthenticated.getCode(), "缺少 currentUserId 必须拒绝，绝不回落到默认操作人");
        verify(service, never()).replay(anyString(), anyString(), anyString(), anyString());

        VideoStatusOutboxReplayVO vo = new VideoStatusOutboxReplayVO();
        vo.setOperationId("op-1");
        vo.setDuplicate(false);
        when(service.replay("evt-1", "op-1", "恢复发布", "42")).thenReturn(vo);

        MockHttpServletRequest admin = new MockHttpServletRequest();
        admin.setAttribute("currentUserId", 42);
        assertEquals(vo, controller.replay("evt-1", request, admin).getData());
        verify(service).replay("evt-1", "op-1", "恢复发布", "42");
    }

    @Test
    void duplicateOperationReturnsNotRepeatedMessage() {
        VideoStatusOutboxReplayService service = mock(VideoStatusOutboxReplayService.class);
        VideoStatusOutboxRepairController controller = new VideoStatusOutboxRepairController(service);
        VideoStatusOutboxReplayVO vo = new VideoStatusOutboxReplayVO();
        vo.setDuplicate(true);
        when(service.replay("evt-1", "op-1", "恢复发布", "42")).thenReturn(vo);

        MockHttpServletRequest admin = new MockHttpServletRequest();
        admin.setAttribute("currentUserId", 42);
        ApiResponse<VideoStatusOutboxReplayVO> response =
                controller.replay("evt-1", request("op-1", "恢复发布"), admin);
        assertEquals("operationId 已完成，未重复执行", response.getMessage());
        assertEquals(vo, response.getData());
    }

    @Test
    void concurrentUniqueKeyConflictBecomesExplicit409() {
        VideoStatusOutboxReplayService service = mock(VideoStatusOutboxReplayService.class);
        VideoStatusOutboxRepairController controller = new VideoStatusOutboxRepairController(service);
        when(service.replay(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("uk_video_status_repair_operation"));

        MockHttpServletRequest admin = new MockHttpServletRequest();
        admin.setAttribute("currentUserId", 42);
        BusinessException conflict = assertThrows(BusinessException.class,
                () -> controller.replay("evt-1", request("op-1", "恢复发布"), admin));
        assertEquals(409, conflict.getCode());
        assertTrue(conflict.getMessage().contains("operationId"));
        assertTrue(conflict.getMessage().contains("已回滚"));
    }

    @Test
    void getStatusPropagatesUnifiedErrorsAndHidesPayloadFields() throws Exception {
        VideoStatusOutboxReplayService service = mock(VideoStatusOutboxReplayService.class);
        VideoStatusOutboxRepairController controller = new VideoStatusOutboxRepairController(service);

        when(service.getStatus("missing")).thenThrow(new BusinessException(404, "没有找到"));
        BusinessException notFound = assertThrows(BusinessException.class,
                () -> controller.getStatus("missing"));
        assertEquals(404, notFound.getCode());

        VideoStatusOutboxStatusVO safe = new VideoStatusOutboxStatusVO();
        safe.setLastErrorPresent(true);
        safe.setLastErrorHint(VideoStatusOutboxReplayService.LAST_ERROR_HINT);
        when(service.getStatus("evt-1")).thenReturn(safe);
        assertEquals(safe, controller.getStatus("evt-1").getData());

        for (Field field : VideoStatusOutboxStatusVO.class.getDeclaredFields()) {
            assertFalse(field.getName().equalsIgnoreCase("payload"), "GET 不允许返回 payload");
            assertFalse(field.getName().equalsIgnoreCase("leaseToken"), "GET 不允许返回 leaseToken");
            assertFalse(field.getName().equalsIgnoreCase("lease_token"));
            assertFalse(field.getName().equalsIgnoreCase("lastError"),
                    "GET 不允许返回 last_error 原文（历史值可能含 payload/凭证）");
        }
        for (Field field : VideoStatusOutboxReplayVO.class.getDeclaredFields()) {
            assertFalse(field.getName().equalsIgnoreCase("lastError"),
                    "POST 响应同样不允许回显 last_error 原文");
        }
        // 只给 bool + 固定提示，不携带任何历史 raw 值。
        assertTrue(safe.getLastErrorPresent());
        assertEquals(VideoStatusOutboxReplayService.LAST_ERROR_HINT, safe.getLastErrorHint());
        assertFalse(safe.getLastErrorHint().contains("{{"));
    }

    private static VideoStatusOutboxReplayDTO request(String operationId, String reason) {
        VideoStatusOutboxReplayDTO request = new VideoStatusOutboxReplayDTO();
        request.setOperationId(operationId);
        request.setReason(reason);
        return request;
    }
}
