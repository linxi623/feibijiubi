package com.feibijiubi.backend.service.impl.auth;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.JwtProperties;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.service.auth.TokenService;
import com.feibijiubi.backend.utils.JwtUtils;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenServiceImpl implements TokenService {
    private final JwtProperties jwtProperties;

    public JwtTokenServiceImpl(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public String createToken(User user) {
        return JwtUtils.createToken(
                user.getId(),
                user.getUsername(),
                user.getRole(),
                jwtProperties.getSecret(),
                jwtProperties.getExpireMinutes()
        );
    }

    @Override
    public Long getUserId(String token) {
        try {
            Long userId = JwtUtils.getUserId(token, jwtProperties.getSecret());
            if (userId == null) {
                throw new BusinessException(401, "请先登录");
            }
            return userId;
        } catch (Exception e) {
            throw new BusinessException(401, "请先登录");
        }
    }
}
