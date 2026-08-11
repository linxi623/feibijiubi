package com.feibijiubi.backend.interceptor;

import com.feibijiubi.backend.annotation.AllowRevokedToken;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.service.auth.TokenContext;
import com.feibijiubi.backend.service.auth.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginInterceptorTests {
    @Mock
    private TokenService tokenService;
    @Mock
    private UserMapper userMapper;

    private LoginInterceptor loginInterceptor;

    @BeforeEach
    void setUp() {
        loginInterceptor = new LoginInterceptor(tokenService, userMapper);
    }

    @Test
    void parsesTokenOnceAndStoresAuthenticationAttributes() throws Exception {
        TokenContext context = tokenContext();
        User user = activeUser();
        when(tokenService.parseToken("token-value")).thenReturn(context);
        when(tokenService.isBlacklisted("jti-1")).thenReturn(false);
        when(userMapper.selectAuthStateById(1)).thenReturn(user);

        MockHttpServletRequest request = requestWithToken();
        boolean result = loginInterceptor.preHandle(
                request,
                new MockHttpServletResponse(),
                handlerMethod("protectedEndpoint")
        );

        assertTrue(result);
        assertEquals(context, request.getAttribute(LoginInterceptor.TOKEN_CONTEXT_ATTRIBUTE));
        assertEquals(1, request.getAttribute("currentUserId"));
        assertEquals((byte) 0, request.getAttribute("currentUserRole"));
        verify(tokenService, times(1)).parseToken("token-value");
    }

    @Test
    void rejectsTokenWhenUserVersionChanged() throws Exception {
        TokenContext context = tokenContext();
        User user = activeUser();
        user.setTokenVersion(1);
        when(tokenService.parseToken("token-value")).thenReturn(context);
        when(tokenService.isBlacklisted("jti-1")).thenReturn(false);
        when(userMapper.selectAuthStateById(1)).thenReturn(user);

        BusinessException exception = assertThrows(BusinessException.class, () ->
                loginInterceptor.preHandle(
                        requestWithToken(),
                        new MockHttpServletResponse(),
                        handlerMethod("protectedEndpoint")
                )
        );

        assertEquals(401, exception.getCode());
    }

    @Test
    void allowsBlacklistedTokenOnlyForIdempotentLogout() throws Exception {
        TokenContext context = tokenContext();
        when(tokenService.parseToken("token-value")).thenReturn(context);
        when(tokenService.isBlacklisted("jti-1")).thenReturn(true);
        when(userMapper.selectAuthStateById(1)).thenReturn(activeUser());

        boolean result = loginInterceptor.preHandle(
                requestWithToken(),
                new MockHttpServletResponse(),
                handlerMethod("logoutEndpoint")
        );

        assertTrue(result);
    }

    @Test
    void rejectsBlacklistedTokenForNormalEndpoint() throws Exception {
        when(tokenService.parseToken("token-value")).thenReturn(tokenContext());
        when(tokenService.isBlacklisted("jti-1")).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () ->
                loginInterceptor.preHandle(
                        requestWithToken(),
                        new MockHttpServletResponse(),
                        handlerMethod("protectedEndpoint")
                )
        );

        assertEquals(401, exception.getCode());
    }

    private MockHttpServletRequest requestWithToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token-value");
        return request;
    }

    private TokenContext tokenContext() {
        return new TokenContext(
                "token-value",
                1,
                (byte) 0,
                "jti-1",
                new Date(),
                new Date(System.currentTimeMillis() + 60_000),
                0
        );
    }

    private User activeUser() {
        User user = new User();
        user.setId(1);
        user.setStatus((byte) 0);
        user.setRole((byte) 0);
        user.setTokenVersion(0);
        return user;
    }

    private HandlerMethod handlerMethod(String methodName) throws NoSuchMethodException {
        return new HandlerMethod(
                new TestController(),
                TestController.class.getDeclaredMethod(methodName)
        );
    }

    private static class TestController {
        void protectedEndpoint() {
        }

        @AllowRevokedToken
        void logoutEndpoint() {
        }
    }
}
