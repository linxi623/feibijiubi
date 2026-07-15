package com.feibijiubi.backend.interceptor;

import com.feibijiubi.backend.annotation.AllowRevokedToken;
import com.feibijiubi.backend.annotation.OptionalLogin;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.service.auth.TokenContext;
import com.feibijiubi.backend.service.auth.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    public static final String TOKEN_CONTEXT_ATTRIBUTE = "tokenContext";

    private final TokenService tokenService;
    private final UserMapper userMapper;

    public LoginInterceptor(TokenService tokenService, UserMapper userMapper) {
        this.tokenService = tokenService;
        this.userMapper = userMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        boolean optionalLogin = handler instanceof HandlerMethod handlerMethod
                && handlerMethod.hasMethodAnnotation(OptionalLogin.class);
        boolean allowRevokedToken = handler instanceof HandlerMethod handlerMethod
                && handlerMethod.hasMethodAnnotation(AllowRevokedToken.class);

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

        TokenContext tokenContext = tokenService.parseToken(token);
        if (tokenService.isBlacklisted(tokenContext.jti()) && !allowRevokedToken) {
            throw new BusinessException(401, "登录已失效");
        }

        User currentUser = userMapper.selectAuthStateById(tokenContext.userId());
        if (currentUser == null) {
            throw new BusinessException(401, "登录状态已失效");
        }
        if (!Objects.equals(currentUser.getStatus(), (byte) 0)) {
            throw new BusinessException(403, "账号状态异常，无法访问");
        }

        int currentTokenVersion = currentUser.getTokenVersion() == null
                ? 0
                : currentUser.getTokenVersion();
        if (!Objects.equals(tokenContext.tokenVersion(), currentTokenVersion)
                || !Objects.equals(tokenContext.role(), currentUser.getRole())) {
            throw new BusinessException(401, "登录状态已失效，请重新登录");
        }

        request.setAttribute(TOKEN_CONTEXT_ATTRIBUTE, tokenContext);
        request.setAttribute("token", token);
        request.setAttribute("currentUserId", tokenContext.userId());
        request.setAttribute("currentUserRole", currentUser.getRole());

        return true;
    }
}
