package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusReconciliationService;
import com.feibijiubi.backend.vo.VideoStatusReconciliationVO;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T10：只读对账接口的管理员鉴权、参数边界与“无写入口”约束。 */
class VideoStatusReconciliationAuthorizationTests {

    @Test
    void reconciliationIsMappedUnderExistingAdminApiWithAdminOnly() throws Exception {
        Class<VideoStatusReconciliationController> type =
                VideoStatusReconciliationController.class;
        assertNotNull(type.getAnnotation(RestController.class));
        RequestMapping classMapping = type.getAnnotation(RequestMapping.class);
        assertNotNull(classMapping);
        assertEquals("/api/admin/video-status", classMapping.value()[0],
                "必须在既有 /api/admin/** 鉴权链路上");

        Method reconcile = type.getDeclaredMethod("reconcile", int.class, int.class);
        assertNotNull(reconcile.getAnnotation(AdminOnly.class),
                "reconcile 必须标注 @AdminOnly");
        assertEquals(ApiResponse.class, reconcile.getReturnType(),
                "成功/错误统一走 ApiResponse");

        List<String> reads = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getAnnotation(GetMapping.class) != null)
                .flatMap(method -> Arrays.stream(
                        method.getAnnotation(GetMapping.class).value()))
                .toList();
        assertEquals(List.of("/reconciliation"), reads,
                "只允许一个只读 GET reconciliation");
        assertNoWriteMappings(type);
    }

    @Test
    void invalidVidOrLimitIsRejectedBeforeCallingService() {
        VideoStatusReconciliationService service =
                mock(VideoStatusReconciliationService.class);
        VideoStatusReconciliationController controller =
                new VideoStatusReconciliationController(service);

        for (int invalidVid : new int[]{0, -1, Integer.MIN_VALUE}) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> controller.reconcile(invalidVid, 100));
            assertEquals(400, error.getCode());
        }
        for (int invalidLimit : new int[]{0, -1, 1001, Integer.MAX_VALUE}) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> controller.reconcile(1, invalidLimit));
            assertEquals(400, error.getCode());
        }
        verify(service, never()).reconcile(anyInt(), anyInt());
    }

    @Test
    void limitBoundariesAreAcceptedAndForwarded() {
        VideoStatusReconciliationService service =
                mock(VideoStatusReconciliationService.class);
        VideoStatusReconciliationVO expected = mock(VideoStatusReconciliationVO.class);
        when(service.reconcile(1, 1)).thenReturn(expected);
        when(service.reconcile(2, 1000)).thenReturn(expected);
        VideoStatusReconciliationController controller =
                new VideoStatusReconciliationController(service);

        assertSame(expected, controller.reconcile(1, 1).getData());
        assertSame(expected, controller.reconcile(2, 1000).getData());

        verify(service).reconcile(1, 1);
        verify(service).reconcile(2, 1000);
    }

    private void assertNoWriteMappings(
            Class<VideoStatusReconciliationController> type) {
        for (Method method : type.getDeclaredMethods()) {
            assertFalse(method.getAnnotation(PostMapping.class) != null
                            || method.getAnnotation(PutMapping.class) != null
                            || method.getAnnotation(PatchMapping.class) != null
                            || method.getAnnotation(DeleteMapping.class) != null,
                    "T10 对账不允许新增写接口: " + method.getName());
        }
    }
}
