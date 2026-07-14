package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.annotation.OptionalLogin;
import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.dto.UserChangePasswordDTO;
import com.feibijiubi.backend.dto.UserProfileDTO;
import com.feibijiubi.backend.service.user.UserService;
import com.feibijiubi.backend.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResponse<UserVO> getCurrentUser(HttpServletRequest request) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        UserVO user = userService.getCurrentUser(currentUserId);
        return ApiResponse.success("查询成功", user);
    }

    @OptionalLogin
    @GetMapping("/{uid}")
    public ApiResponse<UserVO> getUser(@PathVariable Integer uid) {
        UserVO vo = userService.getCurrentUser(uid);
        return ApiResponse.success("查询成功", vo);
    }

    @PutMapping("/me")
    public ApiResponse<Void> updateProfile(HttpServletRequest httprequest,
                                             @Valid @RequestBody UserProfileDTO request) {
        Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
        userService.updateProfile(currentUserId, request);
        return ApiResponse.successMessage("修改成功");
    }

    @PutMapping("/me/password")
    public ApiResponse<Void> updatePassword(HttpServletRequest httprequest,
                                            @Valid @RequestBody UserChangePasswordDTO request) {
        Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
        userService.updatePassword(currentUserId, request);
        return ApiResponse.successMessage("修改成功");
    }

    @PutMapping("/me/avatar")
    public ApiResponse<String> updateAvatar(HttpServletRequest httprequest,
                                           @RequestParam("file") MultipartFile file) {
        Integer currentUserId = (Integer) httprequest.getAttribute("currentUserId");
        String avatarUrl = userService.updateAvatar(currentUserId, file);
        return ApiResponse.success("修改成功", avatarUrl);
    }

    @PostMapping("/{uid}/subscribe")
    public ApiResponse<Void> subscribe(HttpServletRequest request,
                                       @PathVariable Integer uid,
                                       @RequestParam("isSet") Boolean isSet) {
        Integer currentUserId = (Integer) request.getAttribute("currentUserId");
        userService.subscribe(currentUserId, uid, isSet);
        return ApiResponse.success(null);
    }

}
