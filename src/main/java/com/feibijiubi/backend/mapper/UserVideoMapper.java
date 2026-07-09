package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.UserVideo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserVideoMapper {
    UserVideo selectByUidAndVid(@Param("uid")Integer uid,@Param("vid") Integer vid);

    int insert(UserVideo userVideo);

    int updatePlay(UserVideo userVideo);

    int updateLike(UserVideo userVideo);

    int updateCoin(UserVideo userVideo);

    int updateCollect(UserVideo userVideo);
}
