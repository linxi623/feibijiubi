package com.feibijiubi.backend.service.user;

import com.feibijiubi.backend.dto.UserChangePasswordDTO;
import com.feibijiubi.backend.dto.UserProfileDTO;
import com.feibijiubi.backend.vo.UserVO;
import org.springframework.web.multipart.MultipartFile;

public interface UserService {
    UserVO getCurrentUser(Long currentUserId);

    void updateProfile(Long currentUserId, UserProfileDTO request);

    void updatePassword(Long currentUserId, UserChangePasswordDTO request);

    String updateAvatar(Long currentUserId, MultipartFile file);
}
