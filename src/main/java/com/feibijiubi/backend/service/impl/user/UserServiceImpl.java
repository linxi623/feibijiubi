package com.feibijiubi.backend.service.impl.user;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.converter.UserConverter;
import com.feibijiubi.backend.dto.UserChangePasswordDTO;
import com.feibijiubi.backend.dto.UserProfileDTO;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.mapper.UserFollowMapper;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.mapper.VideoMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.storage.FileStorageService;
import com.feibijiubi.backend.service.user.UserService;
import com.feibijiubi.backend.vo.UserCountVO;
import com.feibijiubi.backend.vo.UserPublicProfileVO;
import com.feibijiubi.backend.vo.UserVO;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class UserServiceImpl implements UserService {
    private final UserMapper userMapper;
    private final FileStorageService fileStorageService;
    private final UserFollowMapper userFollowMapper;
    private final VideoMapper videoMapper;
    private final VideoStatusMapper videoStatusMapper;

    public UserServiceImpl(UserMapper userMapper, FileStorageService fileStorageService,
                           UserFollowMapper userFollowMapper, VideoMapper videoMapper,
                           VideoStatusMapper videoStatusMapper) {
        this.userMapper = userMapper;
        this.fileStorageService = fileStorageService;
        this.userFollowMapper = userFollowMapper;
        this.videoMapper = videoMapper;
        this.videoStatusMapper = videoStatusMapper;
    }

    @Override
    public UserVO getCurrentUser(Integer currentUserId) {
        User user = userMapper.selectById(currentUserId);
        if (user == null) {
            throw new BusinessException(401, "登录状态异常，请重新登录");
        }

        if (user.getStatus() != null && user.getStatus() != 0) {
            throw new BusinessException(403, "账号状态异常，无法访问");
        }

        UserCountVO userCount = UserConverter.toUserCountVO(
                userFollowMapper.countFans(currentUserId),
                userFollowMapper.countStar(currentUserId),
                videoStatusMapper.countLikeByUid(currentUserId),
                videoMapper.countVideoByUid(currentUserId)
        );
        UserPublicProfileVO publicProfile = UserConverter.toUserPublicProfileVO(user, userCount, false);
        return UserConverter.toUserVO(user, publicProfile);
    }

    @Override
    public void updateProfile(Integer currentUserId, UserProfileDTO request) {
        loginValidation(currentUserId);

        if(request == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }

        User user = new User();
        user.setId(currentUserId);
        user.setNickname(request.getNickname());
        user.setGender(request.getGender());
        user.setDescription(request.getDescription());
        user.setUpdatedAt(LocalDateTime.now());

        int updatedRows = userMapper.updateProfileById(user);
        if(updatedRows != 1) {
            throw new BusinessException(500, "修改用户资料失败");
        }
    }

    @Override
    public void updatePassword(Integer currentUserId, UserChangePasswordDTO request) {
        validateChangePasswordRequest(request);
        User user = userMapper.selectById(currentUserId);
        if(user == null) {
            throw new BusinessException(401, "登录状态异常，请重新登录");
        }
        if(user.getStatus() != null && user.getStatus() != 0) {
            throw new BusinessException(403, "账号状态异常，无法修改");
        }
        if(!Objects.equals(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "旧密码错误");
        }
        if(Objects.equals(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "新密码不能与旧密码一致");
        }
        user.setPasswordHash(request.getNewPassword());
        userMapper.updatePassword(user);
    }

    @Override
    public String updateAvatar(Integer currentUserId, MultipartFile file) {
        loginValidation(currentUserId);

        String avatarUrl = fileStorageService.uploadImage(currentUserId, file, "avatar/" + currentUserId);

        userMapper.updateAvatar(currentUserId, avatarUrl);

        return avatarUrl;
    }

    private void validateChangePasswordRequest(UserChangePasswordDTO request){
        if(request == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        if(!Objects.equals(request.getNewPassword(), request.getConfirmedPassword())) {
            throw new BusinessException(400, "两次密码不一致");
        }
    }

    private void loginValidation(Integer currentUserId) {
        User user = userMapper.selectById(currentUserId);
        if (user == null) {
            throw new BusinessException(401, "登录状态异常，请重新登录");
        }

        if (user.getStatus() != null && user.getStatus() != 0) {
            throw new BusinessException(403, "账号状态异常，无法访问");
        }
    }
}
