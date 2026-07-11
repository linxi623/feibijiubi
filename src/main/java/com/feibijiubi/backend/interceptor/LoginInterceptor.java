package com.feibijiubi.backend.interceptor;

import com.feibijiubi.backend.annotation.OptionalLogin;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.service.auth.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;

    public LoginInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
    boolean optionalLogin = handler instanceof HandlerMethod handlerMethod
                && handlerMethod.hasMethodAnnotation(OptionalLogin.class);

        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization)) {
            if (optionalLogin) {
                return true;
            }
            throw new BusinessException(401, "请先登录");
        }
        if (!authorization.startsWith("Bearer ")) {
            throw new BusinessException(401, "请先登录");
        }

        String token = authorization.substring(7);
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(401, "请先登录");
        }

        Integer userId = tokenService.getUserId(token);
        Byte role = tokenService.getRole(token);
        request.setAttribute("currentUserId", userId);
        request.setAttribute("currentRole", role);

        return true;
    }
}
