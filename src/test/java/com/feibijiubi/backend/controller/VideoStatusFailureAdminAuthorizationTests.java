package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.dto.VideoStatusFailureAcknowledgeDTO;
import com.feibijiubi.backend.service.admin.VideoStatusFailureAdminService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T10 第一阶段接口权限/路径/DTO/操作人来源的纯单元测试，不启动容器。 */
class VideoStatusFailureAdminAuthorizationTests {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    @Test
    void controllerIsUnderAdminPathAndEveryMethodIsAdminOnly() {
        Class<VideoStatusFailureAdminController> type = VideoStatusFailureAdminController.class;
        assertNotNull(type.getAnnotation(RestController.class));
        RequestMapping mapping = type.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertEquals("/api/admin/video-status/failures", mapping.value()[0]);

        List<String> reads = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getAnnotation(GetMapping.class) != null)
                .peek(m -> assertNotNull(m.getAnnotation(AdminOnly.class), m.getName()))
                .flatMap(m -> Arrays.stream(m.getAnnotation(GetMapping.class).value()))
                .sorted()
                .toList();
        assertEquals(List.of("/{eventId}", "/{eventId}/precheck"), reads);

        List<String> writes = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getAnnotation(PostMapping.class) != null)
                .peek(m -> assertNotNull(m.getAnnotation(AdminOnly.class), m.getName()))
                .flatMap(m -> Arrays.stream(m.getAnnotation(PostMapping.class).value()))
                .toList();
        assertEquals(List.of("/{eventId}/acknowledge"), writes,
                "只提供确认已阅，不提供 ignore/status3 重放/DLQ POST");
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                assertEquals(ApiResponse.class, method.getReturnType(), method.getName());
            }
        }
    }

    @Test
    void acknowledgeDtoRequiresOperationIdAndReasonAndCannotForgeOperator() {
        for (Field field : VideoStatusFailureAcknowledgeDTO.class.getDeclaredFields()) {
            assertFalse(field.getName().equalsIgnoreCase("operator"),
                    "操作人只能来自 currentUserId");
        }
        VideoStatusFailureAcknowledgeDTO blank = new VideoStatusFailureAcknowledgeDTO();
        Set<ConstraintViolation<VideoStatusFailureAcknowledgeDTO>> violations =
                validator.validate(blank);
        assertEquals(2, violations.size());
        VideoStatusFailureAcknowledgeDTO tooLong = new VideoStatusFailureAcknowledgeDTO();
        tooLong.setOperationId("o".repeat(65));
        tooLong.setReason("r");
        assertEquals(1, validator.validate(tooLong).size());
        VideoStatusFailureAcknowledgeDTO ok = new VideoStatusFailureAcknowledgeDTO();
        ok.setOperationId("o".repeat(64));
        ok.setReason("r".repeat(1000));
        assertTrue(validator.validate(ok).isEmpty());
    }

    @Test
    void acknowledgeRequiresLoginAndUsesCurrentUserIdAsOperator() {
        VideoStatusFailureAdminService service = mock(VideoStatusFailureAdminService.class);
        VideoStatusFailureAdminController controller =
                new VideoStatusFailureAdminController(service);
        VideoStatusFailureAcknowledgeDTO dto = request("op-1", "确认已阅");

        BusinessException missing = assertThrows(BusinessException.class,
                () -> controller.acknowledge("e1", "OUTBOX", dto, new MockHttpServletRequest()));
        assertEquals(401, missing.getCode());
        verify(service, never()).acknowledge(
                anyString(), anyString(), anyString(), anyString(), anyString());

        VideoStatusFailureAdminService.AcknowledgeResult result =
                new VideoStatusFailureAdminService.AcknowledgeResult(
                        "op-1", "ACKNOWLEDGE_FAILURE", "OUTBOX", "e1", "42", "COMPLETED",
                        LocalDateTime.now(), false, true, 3, 3, null, null, "ok");
        when(service.acknowledge("OUTBOX", "e1", "op-1", "确认已阅", "42")).thenReturn(result);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("currentUserId", 42);
        assertEquals(result, controller.acknowledge("e1", "OUTBOX", dto, request).getData());
        verify(service).acknowledge("OUTBOX", "e1", "op-1", "确认已阅", "42");
    }

    @Test
    void listRejectsInvalidPagingBeforeCallingService() {
        VideoStatusFailureAdminService service = mock(VideoStatusFailureAdminService.class);
        VideoStatusFailureAdminController controller =
                new VideoStatusFailureAdminController(service);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> controller.list("OUTBOX", 0L, 0)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> controller.list("OUTBOX", -1L, 10)).getCode());
        verify(service, never()).list(anyString(), anyLong(), anyInt());
    }

    private static VideoStatusFailureAcknowledgeDTO request(String operationId, String reason) {
        VideoStatusFailureAcknowledgeDTO dto = new VideoStatusFailureAcknowledgeDTO();
        dto.setOperationId(operationId);
        dto.setReason(reason);
        return dto;
    }
}
