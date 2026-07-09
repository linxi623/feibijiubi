package com.feibijiubi.backend.interceptor;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.service.auth.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Configuration
public class LoginInterceptor implements HandlerInterceptor {
    private final TokenService tokenService;

    public LoginInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String authorization = request.getHeader("Authorization");
        if(!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")){
            throw new BusinessException(401, "请先登录");
        }

        String token = authorization.substring(7);
        if(!StringUtils.hasText(token)) {
            throw new BusinessException(401, "请先登录");
        }

        Integer userId = tokenService.getUserId(token);
        request.setAttribute("currentUserId", userId);

        return true;
    }
}
