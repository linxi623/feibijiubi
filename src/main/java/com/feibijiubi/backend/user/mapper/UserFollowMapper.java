package com.feibijiubi.backend.user.mapper;

import com.feibijiubi.backend.user.entity.UserFollow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserFollowMapper {
    int insert(UserFollow userFollow);

    Boolean checkExist(@Param("followerId")Integer followerId,
                       @Param("followedId") Integer followedId);

    int countFans(Integer userId);

    int countStar(Integer userId);

    int delete(@Param("followerId") Integer followerId,
               @Param("followedId") Integer followedId);
}
