package com.feibijiubi.backend.interaction.mapper;

import com.feibijiubi.backend.interaction.entity.UserVideo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserVideoMapper {
    UserVideo selectByUidAndVid(@Param("uid") Integer uid, @Param("vid") Integer vid);

    int ensureExists(@Param("uid") Integer uid, @Param("vid") Integer vid);

    int updatePlay(UserVideo userVideo);

    int updateLike(UserVideo userVideo);

    int updateCoin(UserVideo userVideo);

    int updateCollect(UserVideo userVideo);
}
