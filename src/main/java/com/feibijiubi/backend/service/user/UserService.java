package com.feibijiubi.backend.service.user;

import com.feibijiubi.backend.dto.UserChangePasswordDTO;
import com.feibijiubi.backend.dto.UserProfileDTO;
import com.feibijiubi.backend.vo.UserVO;
import org.springframework.web.multipart.MultipartFile;

public interface UserService {
    UserVO getCurrentUser(Integer currentUserId);

    void updateProfile(Integer currentUserId, UserProfileDTO request);

    void updatePassword(Integer currentUserId, UserChangePasswordDTO request);

    String updateAvatar(Integer currentUserId, MultipartFile file);
}
