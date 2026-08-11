package com.feibijiubi.backend.service.impl.auth;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.JwtProperties;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.service.auth.TokenContext;
import com.feibijiubi.backend.service.auth.TokenService;
import com.feibijiubi.backend.utils.JwtUtils;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Service
public class JwtTokenServiceImpl implements TokenService {
    private final JwtProperties jwtProperties;
    private final RedisUtils redisUtils;

    public JwtTokenServiceImpl(JwtProperties jwtProperties, RedisUtils redisUtils) {
        this.jwtProperties = jwtProperties;
        this.redisUtils = redisUtils;
    }

    @Override
    public String createToken(User user) {
        return JwtUtils.createToken(
                user.getId(),
                user.getUsername(),
                user.getRole(),
                user.getTokenVersion(),
                jwtProperties.getSecret(),
                jwtProperties.getExpireMinutes()
        );
    }

    @Override
    public TokenContext parseToken(String token) {
        try {
            Claims claims = JwtUtils.parseToken(token, jwtProperties.getSecret());
            Object userId = claims.get("userId");
            Object role = claims.get("role");
            String jti = claims.getId();

            if (userId == null || role == null || !StringUtils.hasText(jti)
                    || claims.getExpiration() == null) {
                throw new BusinessException(401, "登录状态有误");
            }

            return new TokenContext(
                    token,
                    Integer.valueOf(userId.toString()),
                    Byte.valueOf(role.toString()),
                    jti,
                    claims.getIssuedAt(),
                    claims.getExpiration(),
                    JwtUtils.getTokenVersion(claims)
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(401, "请先登录");
        }
    }

    @Override
    public void blacklist(String jti, Duration ttl) {
        if (!StringUtils.hasText(jti)) {
            throw new BusinessException(401, "登录状态异常");
        }

        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new BusinessException(401, "登录状态已经失效");
        }

        redisUtils.setString(
                RedisKeyUtils.jwtToken(jti),
                "1",
                ttl
        );
    }

    @Override
    public boolean isBlacklisted(String jti) {
        return redisUtils.hasKey(RedisKeyUtils.jwtToken(jti));
    }
}
