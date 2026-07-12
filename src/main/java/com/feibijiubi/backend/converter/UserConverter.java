package com.feibijiubi.backend.converter;

import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.vo.UserCountVO;
import com.feibijiubi.backend.vo.UserVO;

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

    public static UserVO toUserVO(User user, UserCountVO userCountVO, Boolean subscribed) {
        if (user == null) {
            return null;
        }

        UserVO userVO = new UserVO();
        userVO.setId(user.getId());
        userVO.setUsername(user.getUsername());
        userVO.setNickname(user.getNickname());
        userVO.setAvatarUrl(user.getAvatarUrl());
        userVO.setBackgroundUrl(user.getBackgroundUrl());
        userVO.setGender(toInteger(user.getGender()));
        userVO.setDescription(user.getDescription());
        userVO.setExperience(user.getExperience());
        userVO.setCoin(user.getCoin());
        userVO.setVip(user.getVip());
        userVO.setStatus(user.getStatus());
        userVO.setRole(user.getRole());
        userVO.setAuth(user.getAuth());
        userVO.setAuthMsg(user.getAuthMsg());
        userVO.setCreatedAt(user.getCreatedAt());
        userVO.setUserCount(userCountVO);
        userVO.setSubscribed(Boolean.TRUE.equals(subscribed));
        return userVO;
    }

    private static Integer toInteger(Byte value) {
        return value == null ? null : value.intValue();
    }

    private static Integer defaultZero(Integer value) {
        return value == null ? 0 : value;
    }
}
