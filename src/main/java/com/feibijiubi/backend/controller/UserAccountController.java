package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.dto.UserLoginDTO;
import com.feibijiubi.backend.dto.UserRegisterDTO;
import com.feibijiubi.backend.service.user.UserAccountService;
import com.feibijiubi.backend.vo.UserLoginVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class UserAccountController {
    private final UserAccountService userAccountService;

    public UserAccountController(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody UserRegisterDTO request) {
        userAccountService.register(request);
        return ApiResponse.successMessage("恭喜你成功注册F站");
    }

    @PostMapping("/login")
    public ApiResponse<UserLoginVO> login(@Valid @RequestBody UserLoginDTO request) {
        UserLoginVO loginResult = userAccountService.login(request);
        return ApiResponse.success("登录成功", loginResult);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        userAccountService.logout(currentUserId);
        return ApiResponse.successMessage("退出成功");
    }

}
