package com.feibijiubi.backend.service.impl.user;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.converter.UserConverter;
import com.feibijiubi.backend.dto.UserLoginDTO;
import com.feibijiubi.backend.dto.UserRegisterDTO;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.service.auth.TokenContext;
import com.feibijiubi.backend.service.auth.TokenService;
import com.feibijiubi.backend.service.ratelimit.RateLimitService;
import com.feibijiubi.backend.service.user.UserAccountService;
import com.feibijiubi.backend.vo.UserLoginVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class UserAccountServiceImpl implements UserAccountService {
    private final UserMapper userMapper;
    private final TokenService tokenService;
    private final RateLimitService rateLimitService;


    @Override
    public void register(UserRegisterDTO request) {
        validateRegisterRequest(request);

        int usernameCount = userMapper.countByUsername(request.getUsername());
        if (usernameCount >= 1) {
            throw new BusinessException(400, "用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        // TODO 后续接入密码加密后，这里应保存加密后的密码摘要。
        user.setPasswordHash(request.getPassword());
        user.setNickname("用户" + request.getUsername());

        userMapper.createUser(user);
    }

    @Override
    public UserLoginVO login(UserLoginDTO request) {
        if (request == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        String username = request.getUsername();
        String password = request.getPassword();

        rateLimitService.checkLoginFailureLimit(username);

        User user = userMapper.selectByUsernameForLogin(username);

        if(user == null || !Objects.equals(password, user.getPasswordHash())) {
            rateLimitService.recordLoginFailure(username);
            throw new BusinessException(400, "用户名或密码错误");
        }

        if (user.getStatus() != null && user.getStatus() != 0) {
            throw new BusinessException(403, "账号状态异常，无法登录");
        }

        rateLimitService.clearLoginFailures(username);

        UserLoginVO loginVO = new UserLoginVO();
        loginVO.setToken(tokenService.createToken(user));
        return loginVO;
    }

    @Override
    public void logout(TokenContext tokenContext) {
        if (tokenContext == null || !StringUtils.hasText(tokenContext.jti())) {
            throw new BusinessException(401, "登录状态失效");
        }
        if (tokenService.isBlacklisted(tokenContext.jti())) {
            return;
        }

        Duration remainingTtl = Duration.ofMillis(
                tokenContext.expireTime().getTime() - System.currentTimeMillis()
        );
        if (remainingTtl.isZero() || remainingTtl.isNegative()) {
            throw new BusinessException(401, "登录状态已失效");
        }
        tokenService.blacklist(tokenContext.jti(), remainingTtl);
    }

    private void validateRegisterRequest(UserRegisterDTO request) {
        if (request == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        if (!Objects.equals(request.getPassword(), request.getConfirmedPassword())) {
            throw new BusinessException(400, "前后两次密码输入不一致");
        }
    }

}
