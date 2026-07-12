package com.feibijiubi.backend.interceptor;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        Byte roleCode = (Byte) request.getAttribute("currentUserRole");
        UserRole role = UserRole.fromCode(roleCode);

        if (role != UserRole.ADMIN && role != UserRole.SUPERADMIN) {
            throw new BusinessException(403, "你的权限不足");
        }

        return true;
    }
}

