package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.auth.annotation.OptionalLogin;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.auth.interceptor.LoginInterceptor;
import com.feibijiubi.backend.user.mapper.UserMapper;
import com.feibijiubi.backend.auth.service.TokenService;
import com.feibijiubi.backend.service.impl.video.UserVideoServiceImpl;
import com.feibijiubi.backend.service.video.UserVideoService;
import com.feibijiubi.backend.vo.VideoInteractionVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T15 第二阶段 HTTP 接线的结构 + 行为测试（不需要启动 Web 容器）。
 *
 * <p>固定的契约：</p>
 * <ol>
 *   <li>{@code play-count} / {@code share} 两个接口仍然只要求<b>可选登录</b>，但都必须接收
 *       {@code Idempotency-Key}（必填）与 {@code X-Visitor-Id}（匿名必填）；</li>
 *   <li>两个头都用 {@code required = false} 声明，缺失由服务层给出 400 而不是由 Spring 抛出
 *       未被全局异常处理器覆盖的 {@code MissingRequestHeaderException}（那会变成 500）；</li>
 *   <li>非法 Token 依旧由 {@link LoginInterceptor} 拒绝——带一个 {@code X-Visitor-Id} 头
 *       不能把“token 坏了”变成“匿名请求”；</li>
 *   <li>旧的无 actor/无幂等键签名必须彻底消失，且播放/分享链路没有外层 {@code @Transactional}。</li>
 * </ol>
 */
class VideoInteractionHttpMappingTests {

    private static final List<String> INTERACTION_METHODS =
            List.of("increasePlayCount", "increaseShare");

    @Test
    void playAndShareDeclareIdempotencyAndVisitorHeadersAsOptional() throws Exception {
        for (String name : INTERACTION_METHODS) {
            Method method = controllerMethod(name);

            List<String> headerNames = Arrays.stream(method.getParameterAnnotations())
                    .flatMap(Arrays::stream)
                    .filter(RequestHeader.class::isInstance)
                    .map(RequestHeader.class::cast)
                    .map(RequestHeader::value)
                    .toList();
            assertEquals(List.of("Idempotency-Key", "X-Visitor-Id"), headerNames,
                    name + " 必须接收 Idempotency-Key 与 X-Visitor-Id");

            for (var annotations : method.getParameterAnnotations()) {
                for (var annotation : annotations) {
                    if (annotation instanceof RequestHeader header) {
                        assertFalse(header.required(),
                                header.value() + " 必须 required=false，缺失由服务层返回 400");
                    }
                }
            }
        }
    }

    @Test
    void playAndShareStayOptionalLoginAndReturnInteractionVo() throws Exception {
        for (String name : INTERACTION_METHODS) {
            Method method = controllerMethod(name);
            assertNotNull(method.getAnnotation(OptionalLogin.class),
                    name + " 必须保持可选登录（匿名也能播放/分享）");
            assertNotNull(method.getAnnotation(PostMapping.class));

            ParameterizedType returnType = (ParameterizedType) method.getGenericReturnType();
            assertEquals(ApiResponse.class, returnType.getRawType());
            assertEquals(VideoInteractionVO.class, returnType.getActualTypeArguments()[0],
                    "对外只返回 counted/duplicate/reason 的 VO");
        }

        assertEquals(List.of("/{vid}/play-count"),
                List.of(controllerMethod("increasePlayCount").getAnnotation(PostMapping.class).value()));
        assertEquals(List.of("/{vid}/share"),
                List.of(controllerMethod("increaseShare").getAnnotation(PostMapping.class).value()));
    }

    @Test
    void controllerPassesLoginStateAndRawHeadersToService() throws Exception {
        UserVideoService userVideoService = mock(UserVideoService.class);
        UserVideoController controller = new UserVideoController(userVideoService);
        VideoInteractionVO counted = VideoInteractionVO.counted(false);
        when(userVideoService.increasePlayCount(eq(42), eq(7), eq("key-1"), eq("visitor-1")))
                .thenReturn(counted);
        when(userVideoService.increaseShare(isNull(), eq(7), eq("key-2"), eq("visitor-2")))
                .thenReturn(VideoInteractionVO.windowSuppressed(false));

        MockHttpServletRequest loggedIn = new MockHttpServletRequest();
        loggedIn.setAttribute("currentUserId", 42);
        assertEquals(counted, controller.increasePlayCount(7, "key-1", "visitor-1", loggedIn)
                .getData());

        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        ApiResponse<VideoInteractionVO> anonymousResponse =
                controller.increaseShare(7, "key-2", "visitor-2", anonymous);
        assertFalse(anonymousResponse.getData().counted());

        verify(userVideoService).increasePlayCount(42, 7, "key-1", "visitor-1");
        verify(userVideoService).increaseShare(null, 7, "key-2", "visitor-2");
    }

    @Test
    void invalidTokenIsRejectedEvenWhenVisitorHeaderIsPresent() throws Exception {
        TokenService tokenService = mock(TokenService.class);
        UserMapper userMapper = mock(UserMapper.class);
        when(tokenService.parseToken("bad-token"))
                .thenThrow(new BusinessException(401, "登录凭证无效"));
        LoginInterceptor interceptor = new LoginInterceptor(tokenService, userMapper);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad-token");
        request.addHeader("X-Visitor-Id", "3f2a9c74-0f39-4ccc-9f24-08a4c1f3f2a1");

        BusinessException error = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(),
                        handlerMethod("increasePlayCount")));

        assertEquals(401, error.getCode());
        assertNull(request.getAttribute("currentUserId"),
                "非法 token 不得被 X-Visitor-Id 降级成匿名身份");
        verifyNoInteractions(userMapper);
    }

    @Test
    void missingTokenIsAllowedButVisitorHeaderDoesNotAuthenticate() throws Exception {
        LoginInterceptor interceptor = new LoginInterceptor(
                mock(TokenService.class), mock(UserMapper.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Visitor-Id", "3f2a9c74-0f39-4ccc-9f24-08a4c1f3f2a1");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(),
                handlerMethod("increaseShare")));
        assertNull(request.getAttribute("currentUserId"),
                "X-Visitor-Id 只是匿名标识，绝不是登录态");
    }

    @Test
    void legacyUnlimitedSignaturesAreGone() {
        List<String> singleArgumentSignatures = Arrays.stream(UserVideoService.class.getMethods())
                .filter(method -> method.getName().equals("increasePlayCount")
                        || method.getName().equals("increaseShare"))
                .filter(method -> method.getParameterCount() == 1)
                .map(Method::toString)
                .toList();

        assertTrue(singleArgumentSignatures.isEmpty(),
                "不得保留无 actor/无幂等键的重载，否则它是一条绕过准入的计数入口: "
                        + singleArgumentSignatures);

        for (String name : INTERACTION_METHODS) {
            Method method = Arrays.stream(UserVideoServiceImpl.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst()
                    .orElseThrow();
            assertNull(method.getAnnotation(Transactional.class),
                    name + " 不得有外层事务：record() 要求调用方不在事务里");
        }
    }

    @Test
    void otherInteractionEndpointsKeepTheirLoginRequirement() throws Exception {
        for (String name : List.of("savePlayProgress", "recordLike", "increaseCoin", "Collect")) {
            assertNull(controllerMethod(name).getAnnotation(OptionalLogin.class),
                    name + " 不在本次改动范围内，必须保持“必须登录”");
        }
        assertNotNull(controllerMethod("savePlayProgress"));
        assertNotNull(controllerMethod("recordLike"));
        assertNotNull(controllerMethod("increaseCoin"));
        assertNotNull(controllerMethod("Collect"));
    }

    // -------------------------------------------------------------------- helpers

    private static Method controllerMethod(String name) throws NoSuchMethodException {
        return Arrays.stream(UserVideoController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(name));
    }

    private static HandlerMethod handlerMethod(String name) throws NoSuchMethodException {
        return new HandlerMethod(new UserVideoController(mock(UserVideoService.class)),
                controllerMethod(name));
    }
}
