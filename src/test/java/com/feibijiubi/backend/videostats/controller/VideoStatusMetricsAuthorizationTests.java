package com.feibijiubi.backend.videostats.controller;

import com.feibijiubi.backend.auth.annotation.AdminOnly;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.WebMvcConfig;
import com.feibijiubi.backend.auth.interceptor.AdminInterceptor;
import com.feibijiubi.backend.auth.interceptor.LoginInterceptor;
import com.feibijiubi.backend.videostats.metrics.VideoStatusBacklogSampler;
import com.feibijiubi.backend.videostats.metrics.VideoStatusTelemetry;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.videostats.vo.VideoStatusGateVO;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T05：指标接口必须落在既有管理员鉴权链路上，且门闩只读观测不伪造年龄。 */
class VideoStatusMetricsAuthorizationTests {

    @Test
    void metricsControllerIsMappedUnderAdminApi() {
        Class<VideoStatusMetricsController> type = VideoStatusMetricsController.class;
        RequestMapping classMapping = type.getAnnotation(RequestMapping.class);
        assertNotNull(classMapping);
        assertEquals("/api/admin/video-status", classMapping.value()[0]);
        assertNotNull(type.getAnnotation(RestController.class));

        for (String methodName : List.of("getMetrics", "getGate")) {
            Method method = java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            assertNotNull(method.getAnnotation(AdminOnly.class),
                    methodName + " 必须标注 @AdminOnly");
            assertEquals(ApiResponse.class, method.getReturnType());
        }
    }

    @Test
    void adminInterceptorCoversEveryAdminPath() throws Exception {
        LoginInterceptor loginInterceptor = mock(LoginInterceptor.class);
        AdminInterceptor adminInterceptor = mock(AdminInterceptor.class);
        InterceptorRegistry registry = new InterceptorRegistry();

        new WebMvcConfig(loginInterceptor, adminInterceptor).addInterceptors(registry);

        // Spring 6 只开放 protected 访问器：用反射取出映射后的 MappedInterceptor，不启动 Web 容器。
        Method getInterceptors = InterceptorRegistry.class.getDeclaredMethod("getInterceptors");
        getInterceptors.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> registrations = (List<Object>) getInterceptors.invoke(registry);

        boolean covered = registrations.stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(mapped -> mapped.getInterceptor() == adminInterceptor)
                .flatMap(mapped -> java.util.Arrays.stream(mapped.getIncludePathPatterns()))
                .anyMatch("/api/admin/**"::equals);
        assertTrue(covered, "指标接口依赖既有 /api/admin/** 拦截器");
    }

    @Test
    void gateEndpointReadsTokenWithoutWriting() {
        VideoStatusTelemetry telemetry = mock(VideoStatusTelemetry.class);
        VideoStatusBacklogSampler sampler = mock(VideoStatusBacklogSampler.class);
        RedisUtils redisUtils = mock(RedisUtils.class);
        VideoStatusMetricsController controller =
                new VideoStatusMetricsController(telemetry, sampler, redisUtils);

        assertThrows(BusinessException.class, () -> controller.getGate(0));
        assertThrows(BusinessException.class, () -> controller.getGate(null));

        when(redisUtils.getString(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        VideoStatusGateVO absent = controller.getGate(7).getData();
        assertFalse(absent.isPresent());
        assertEquals("ABSENT", absent.getTokenFormat());
        assertNull(absent.getTokenAgeMillis());

        when(redisUtils.getString(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("3f2a9c74-0f39-4ccc-9f24-08a4c1f3f2a1");
        VideoStatusGateVO legacy = controller.getGate(7).getData();
        assertTrue(legacy.isPresent());
        assertEquals("LEGACY_UNKNOWN", legacy.getTokenFormat());
        assertNull(legacy.getTokenAgeMillis(), "老格式必须返回未知而不是猜一个年龄");

        long createdAt = System.currentTimeMillis() - 5_000L;
        when(redisUtils.getString(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(createdAt + "-3f2a9c74-0f39-4ccc-9f24-08a4c1f3f2a1");
        VideoStatusGateVO fresh = controller.getGate(7).getData();
        assertEquals("EPOCH_MILLIS_TOKEN", fresh.getTokenFormat());
        assertNotNull(fresh.getTokenAgeMillis());
        assertTrue(fresh.getTokenAgeMillis() >= 5_000L);
    }

    @Test
    void metricsUseGetAndDoNotExposeActuatorPaths() {
        List<String> paths = java.util.Arrays.stream(VideoStatusMetricsController.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(GetMapping.class) != null)
                .flatMap(method -> java.util.Arrays.stream(method.getAnnotation(GetMapping.class).value()))
                .sorted()
                .toList();

        assertEquals(List.of("/gate", "/metrics"), paths);
        assertTrue(paths.stream().noneMatch(path -> path.startsWith("/actuator")));
    }
}
