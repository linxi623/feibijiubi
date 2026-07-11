package com.feibijiubi.backend.converter;

import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.vo.UserVO;

import java.util.Collections;
import java.util.List;

public final class UserConverter {
    private UserConverter() {
    }

    public static UserVO toUserVO(User user) {
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
        userVO.setVip(toInteger(user.getVip()));
        userVO.setStatus(toInteger(user.getStatus()));
        userVO.setRole(toInteger(user.getRole()));
        userVO.setAuth(toInteger(user.getAuth()));
        userVO.setAuthMsg(user.getAuthMsg());
        userVO.setCreatedAt(user.getCreatedAt());
        return userVO;
    }

    public static List<UserVO> toUserVOList(List<User> users) {
        if (users == null || users.isEmpty()) {
            return Collections.emptyList();
        }

        return users.stream()
                .map(UserConverter::toUserVO)
                .toList();
    }

    private static Integer toInteger(Byte value) {
        return value == null ? null : value.intValue();
    }
}
