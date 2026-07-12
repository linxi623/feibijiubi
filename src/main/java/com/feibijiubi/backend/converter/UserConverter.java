package com.feibijiubi.backend.converter;

import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.vo.UserCountVO;
import com.feibijiubi.backend.vo.UserPublicProfileVO;
import com.feibijiubi.backend.vo.UserVO;

import java.util.Collections;
import java.util.List;

public final class UserConverter {
    private UserConverter() {
    }

    public static UserCountVO toUserCountVO(Integer fansCount, Integer starCount,
                                          Integer loveCount, Integer videoCount) {
        UserCountVO userCountVO = new UserCountVO();
        userCountVO.setFansCount(defaultZero(fansCount));
        userCountVO.setStarCount(defaultZero(starCount));
        userCountVO.setLoveCount(defaultZero(loveCount));
        userCountVO.setVideoCount(defaultZero(videoCount));
        return userCountVO;
    }

    public static UserVO toUserVO(User user, UserPublicProfileVO userPublicProfileVO) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        userVO.setUsername(user.getUsername());
        userVO.setCoin(user.getCoin());
        userVO.setCreatedAt(user.getCreatedAt());
        userVO.setUserPublicProfile(userPublicProfileVO);
        return userVO;
    }

    private static Integer toInteger(Byte value) {
        return value == null ? null : value.intValue();
    }

    private static Integer defaultZero(Integer value) {
        return value == null ? 0 : value;
    }

    public static UserPublicProfileVO toUserPublicProfileVO(User user, UserCountVO userCountVO, Boolean subscribed) {
        if (user == null) {
            return null;
        }
        UserPublicProfileVO userPublicProfileVO = new UserPublicProfileVO();
        userPublicProfileVO.setId(user.getId());
        userPublicProfileVO.setNickname(user.getNickname());
        userPublicProfileVO.setAvatarUrl(user.getAvatarUrl());
        userPublicProfileVO.setBackgroundUrl(user.getBackgroundUrl());
        userPublicProfileVO.setGender(toInteger(user.getGender()));
        userPublicProfileVO.setDescription(user.getDescription());
        userPublicProfileVO.setExperience(user.getExperience());
        userPublicProfileVO.setVip(user.getVip());
        userPublicProfileVO.setStatus(user.getStatus());
        userPublicProfileVO.setRole(user.getRole());
        userPublicProfileVO.setAuth(user.getAuth());
        userPublicProfileVO.setAuthMsg(user.getAuthMsg());
        userPublicProfileVO.setUserCount(userCountVO);
        userPublicProfileVO.setSubscribed(subscribed);

        return userPublicProfileVO;
    }
}
