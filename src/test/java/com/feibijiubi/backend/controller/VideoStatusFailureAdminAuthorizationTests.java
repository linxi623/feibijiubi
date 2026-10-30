package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.auth.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.WebMvcConfig;
import com.feibijiubi.backend.dto.VideoStatusFailureAcknowledgeDTO;
import com.feibijiubi.backend.dto.VideoStatusStatus3ReplayDTO;
import com.feibijiubi.backend.auth.interceptor.AdminInterceptor;
import com.feibijiubi.backend.auth.interceptor.LoginInterceptor;
import com.feibijiubi.backend.service.admin.VideoStatusFailureAdminService;
import com.feibijiubi.backend.service.admin.VideoStatusStatus3ReplayService;
import com.feibijiubi.backend.vo.VideoStatusStatus3ReplayVO;
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
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

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
        assertEquals(List.of("/{eventId}/acknowledge", "/{eventId}/replay"), writes.stream().sorted().toList(),
                "只提供确认已阅与 T10-B1 consumed status3 受控重放，不提供 ignore/DLQ 批量 POST");
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                assertEquals(ApiResponse.class, method.getReturnType(), method.getName());
            }
        }
    }

    @Test
    void failureAdminPathsAreCoveredByLoginAndAdminInterceptorsInWebMvcConfig() throws Exception {
        LoginInterceptor loginInterceptor = mock(LoginInterceptor.class);
        AdminInterceptor adminInterceptor = mock(AdminInterceptor.class);
        InterceptorRegistry registry = new InterceptorRegistry();

        new WebMvcConfig(loginInterceptor, adminInterceptor).addInterceptors(registry);

        Method getInterceptors = InterceptorRegistry.class.getDeclaredMethod("getInterceptors");
        getInterceptors.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> registrations = (List<Object>) getInterceptors.invoke(registry);

        List<MappedInterceptor> mapped = registrations.stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .toList();
        assertTrue(mapped.stream()
                        .filter(candidate -> candidate.getInterceptor() == adminInterceptor)
                        .flatMap(candidate -> Arrays.stream(
                                candidate.getIncludePathPatterns()))
                        .anyMatch("/api/admin/**"::equals),
                "失败事件接口必须经过 AdminInterceptor 的 /api/admin/** 路径");
        assertTrue(mapped.stream()
                        .filter(candidate -> candidate.getInterceptor() == loginInterceptor)
                        .flatMap(candidate -> Arrays.stream(
                                candidate.getIncludePathPatterns()))
                        .anyMatch("/api/**"::equals),
                "失败事件接口必须经过 LoginInterceptor 的 /api/** 登录路径");
        assertFalse(mapped.stream()
                        .filter(candidate -> candidate.getInterceptor() == loginInterceptor)
                        .flatMap(candidate -> Arrays.stream(
                                candidate.getExcludePathPatterns()))
                        .anyMatch(pattern -> pattern.contains("video-status/failures")),
                "失败事件接口不允许被加入登录排除列表");
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
        VideoStatusStatus3ReplayService replayService = mock(VideoStatusStatus3ReplayService.class);
        VideoStatusFailureAdminController controller =
                new VideoStatusFailureAdminController(service, replayService);
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
    void status3ReplayRequiresLoginAndUsesCurrentUserIdAsOperator() {
        VideoStatusFailureAdminService service = mock(VideoStatusFailureAdminService.class);
        VideoStatusStatus3ReplayService replayService = mock(VideoStatusStatus3ReplayService.class);
        VideoStatusFailureAdminController controller =
                new VideoStatusFailureAdminController(service, replayService);
        VideoStatusStatus3ReplayDTO dto = replayRequest("op-2", "受控重放");

        BusinessException missing = assertThrows(BusinessException.class,
                () -> controller.replayStatus3("e2", "CONSUMED", dto, new MockHttpServletRequest()));
        assertEquals(401, missing.getCode());
        verify(replayService, never()).replay(
                anyString(), anyString(), anyString(), anyString(), anyString());

        VideoStatusStatus3ReplayVO applied = new VideoStatusStatus3ReplayVO();
        applied.setOperationId("op-2");
        applied.setReplayApplied(true);
        when(replayService.replay("CONSUMED", "e2", "op-2", "受控重放", "42"))
                .thenReturn(applied);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("currentUserId", 42);
        assertEquals(applied, controller.replayStatus3("e2", "CONSUMED", dto, request).getData());
        verify(replayService).replay("CONSUMED", "e2", "op-2", "受控重放", "42");
    }

    @Test
    void status3ReplayDtoRequiresOperationIdAndReasonAndCannotForgeTargetOrOperator() {
        for (Field field : VideoStatusStatus3ReplayDTO.class.getDeclaredFields()) {
            assertFalse(field.getName().equalsIgnoreCase("operator"),
                    "操作人只能来自 currentUserId");
            assertFalse(field.getName().equalsIgnoreCase("eventId"),
                    "目标只能来自 URL 路径，不能由请求体指定");
            assertFalse(field.getName().equalsIgnoreCase("vid"),
                    "目标视频只能来自数据库行，不能由请求体指定");
            assertFalse(field.getName().equalsIgnoreCase("payload"),
                    "事件内容只能来自数据库，不能由请求体构造");
        }
        VideoStatusStatus3ReplayDTO blank = new VideoStatusStatus3ReplayDTO();
        assertEquals(2, validator.validate(blank).size());
        VideoStatusStatus3ReplayDTO tooLong = new VideoStatusStatus3ReplayDTO();
        tooLong.setOperationId("o".repeat(65));
        tooLong.setReason("r");
        assertEquals(1, validator.validate(tooLong).size());
        VideoStatusStatus3ReplayDTO ok = new VideoStatusStatus3ReplayDTO();
        ok.setOperationId("o".repeat(64));
        ok.setReason("r".repeat(1000));
        assertTrue(validator.validate(ok).isEmpty());
    }

    @Test
    void listRejectsInvalidPagingBeforeCallingService() {
        VideoStatusFailureAdminService service = mock(VideoStatusFailureAdminService.class);
        VideoStatusStatus3ReplayService replayService = mock(VideoStatusStatus3ReplayService.class);
        VideoStatusFailureAdminController controller =
                new VideoStatusFailureAdminController(service, replayService);
        assertEquals(400, assertThrows(BusinessException.class,
                () -> controller.list("OUTBOX", 0L, 0)).getCode());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> controller.list("OUTBOX", -1L, 10)).getCode());
        verify(service, never()).list(anyString(), anyLong(), anyInt());
    }

    private static VideoStatusStatus3ReplayDTO replayRequest(String operationId, String reason) {
        VideoStatusStatus3ReplayDTO dto = new VideoStatusStatus3ReplayDTO();
        dto.setOperationId(operationId);
        dto.setReason(reason);
        return dto;
    }

    private static VideoStatusFailureAcknowledgeDTO request(String operationId, String reason) {
        VideoStatusFailureAcknowledgeDTO dto = new VideoStatusFailureAcknowledgeDTO();
        dto.setOperationId(operationId);
        dto.setReason(reason);
        return dto;
    }
}
