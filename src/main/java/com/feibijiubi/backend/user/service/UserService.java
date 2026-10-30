package com.feibijiubi.backend.user.service;

import com.feibijiubi.backend.user.dto.UserChangePasswordDTO;
import com.feibijiubi.backend.user.dto.UserProfileDTO;
import com.feibijiubi.backend.user.vo.UserVO;
import org.springframework.web.multipart.MultipartFile;

public interface UserService {
    UserVO getCurrentUser(Integer currentUserId);

    UserVO getUser(Integer currentUserId, Integer uid);

    void updateProfile(Integer currentUserId, UserProfileDTO request);

    void updatePassword(Integer currentUserId, UserChangePasswordDTO request);

    String updateAvatar(Integer currentUserId, MultipartFile file);

    String updateBackground(Integer currentUserId, MultipartFile file);

    void subscribe(Integer currentUserId, Integer uid, Boolean isSet);
}
