package com.feibijiubi.backend.service.impl.user;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.converter.UserConverter;
import com.feibijiubi.backend.dto.UserLoginDTO;
import com.feibijiubi.backend.dto.UserRegisterDTO;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.service.auth.TokenService;
import com.feibijiubi.backend.service.user.UserAccountService;
import com.feibijiubi.backend.vo.UserLoginVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Service
public class UserAccountServiceImpl implements UserAccountService {
    private final UserMapper userMapper;
    private final TokenService tokenService;

    public UserAccountServiceImpl(UserMapper userMapper, TokenService tokenService) {
        this.userMapper = userMapper;
        this.tokenService = tokenService;
    }

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
        User user = userMapper.selectByUsernameForLogin(request.getUsername());
        if (user == null ||
                !Objects.equals(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "用户名或密码错误");
        }

        if (user.getStatus() != null && user.getStatus() != 0) {
            throw new BusinessException(403, "账号状态异常，无法登录");
        }

        UserLoginVO loginVO = new UserLoginVO();
        loginVO.setToken(tokenService.createToken(user));
        loginVO.setUser(UserConverter.toUserVO(user));
        return loginVO;
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
