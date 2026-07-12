package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.UserFollow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserFollowMapper {
    int insert(UserFollow userFollow);

    Boolean checkExist(@Param("followerId")Integer followerId,
                       @Param("followedId") Integer followedId);

    int countFans(Integer userId);

    int countStar(Integer userId);
}
