package com.feibijiubi.backend.service.user;

import com.feibijiubi.backend.dto.UserLoginDTO;
import com.feibijiubi.backend.dto.UserRegisterDTO;
import com.feibijiubi.backend.vo.UserLoginVO;

public interface UserAccountService {
    void register(UserRegisterDTO request);

    UserLoginVO login(UserLoginDTO request);
}
