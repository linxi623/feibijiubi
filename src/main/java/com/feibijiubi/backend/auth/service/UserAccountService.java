package com.feibijiubi.backend.auth.service;

import com.feibijiubi.backend.auth.dto.UserLoginDTO;
import com.feibijiubi.backend.auth.dto.UserRegisterDTO;
import com.feibijiubi.backend.auth.vo.UserLoginVO;

public interface UserAccountService {
    void register(UserRegisterDTO request);

    UserLoginVO login(UserLoginDTO request);

    void logout(TokenContext tokenContext);
}
