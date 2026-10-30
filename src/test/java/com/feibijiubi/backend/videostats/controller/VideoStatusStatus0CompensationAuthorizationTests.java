package com.feibijiubi.backend.videostats.controller;

import com.feibijiubi.backend.auth.annotation.AdminOnly;
import com.feibijiubi.backend.shared.ApiResponse;
import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.videostats.service.VideoStatusStatus0CompensationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T09：status0 补偿只读 preview 的管理员鉴权、参数边界与只读约束。 */
class VideoStatusStatus0CompensationAuthorizationTests {

    @Test
    void previewIsMappedUnderExistingAdminApiWithAdminOnly() throws Exception {
        Class<VideoStatusStatus0CompensationController> type =
                VideoStatusStatus0CompensationController.class;
        assertNotNull(type.getAnnotation(RestController.class));
        RequestMapping classMapping = type.getAnnotation(RequestMapping.class);
        assertNotNull(classMapping);
        assertEquals("/api/admin/video-status/status0-compensation",
                classMapping.value()[0], "必须在既有 /api/admin/** 鉴权链路上");

        Method preview = type.getDeclaredMethod(
                "preview", int.class, long.class);
        assertNotNull(preview.getAnnotation(AdminOnly.class),
                "preview 必须标注 @AdminOnly");
        assertEquals(ApiResponse.class, preview.getReturnType(),
                "成功/错误统一走 ApiResponse");

        List<String> reads = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getAnnotation(GetMapping.class) != null)
                .flatMap(method -> Arrays.stream(
                        method.getAnnotation(GetMapping.class).value()))
                .toList();
        assertEquals(List.of("/preview"), reads, "只允许一个只读 GET preview");
        assertTrueNoWriteMappings(type);
    }

    @Test
    void previewRemainsAccessibleWhenCompensationIsDisabled() {
        VideoStatusStatus0CompensationService service =
                mock(VideoStatusStatus0CompensationService.class);
        VideoStatusStatus0CompensationService.PreviewResult disabled =
                new VideoStatusStatus0CompensationService.PreviewResult(
                        0L, false, false, List.of());
        when(service.preview(10, 0L)).thenReturn(disabled);
        VideoStatusStatus0CompensationController controller =
                new VideoStatusStatus0CompensationController(service);

        ApiResponse<VideoStatusStatus0CompensationService.PreviewResult> response =
                controller.preview(10, 0L);

        assertEquals(disabled, response.getData());
        verify(service).preview(10, 0L);
    }

    @Test
    void limitAndAfterIdAreValidatedBeforeCallingService() {
        VideoStatusStatus0CompensationService service =
                mock(VideoStatusStatus0CompensationService.class);
        VideoStatusStatus0CompensationController controller =
                new VideoStatusStatus0CompensationController(service);

        for (int invalidLimit : new int[]{0, -1, 101}) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> controller.preview(invalidLimit, 0L));
            assertEquals(400, error.getCode());
        }
        BusinessException negativeAfterId = assertThrows(BusinessException.class,
                () -> controller.preview(10, -1L));
        assertEquals(400, negativeAfterId.getCode());
        verify(service, never()).preview(anyInt(), anyLong());
    }

    @Test
    void previewBoundariesAreAcceptedAndForwardedReadOnly() {
        VideoStatusStatus0CompensationService service =
                mock(VideoStatusStatus0CompensationService.class);
        VideoStatusStatus0CompensationService.PreviewResult empty =
                new VideoStatusStatus0CompensationService.PreviewResult(
                        0L, false, false, List.of());
        when(service.preview(anyInt(), anyLong())).thenReturn(empty);
        VideoStatusStatus0CompensationController controller =
                new VideoStatusStatus0CompensationController(service);

        controller.preview(1, 0L);
        controller.preview(100, Long.MAX_VALUE);

        verify(service).preview(1, 0L);
        verify(service).preview(100, Long.MAX_VALUE);
        verify(service, never()).runOnce(anyLong());
    }

    private void assertTrueNoWriteMappings(
            Class<VideoStatusStatus0CompensationController> type) {
        for (Method method : type.getDeclaredMethods()) {
            assertFalse(method.getAnnotation(PostMapping.class) != null
                            || method.getAnnotation(PutMapping.class) != null
                            || method.getAnnotation(PatchMapping.class) != null
                            || method.getAnnotation(DeleteMapping.class) != null,
                    "T09 不允许新增写接口: " + method.getName());
        }
    }
}
