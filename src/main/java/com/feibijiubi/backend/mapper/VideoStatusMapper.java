package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.VideoStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;


@Mapper
public interface VideoStatusMapper {
    int insertDefault(Integer vid);

    VideoStatus selectByVid(Integer vid);

    int increasePlayTimes(Integer vid);

    int increaseLikeTimes(@Param("vid")Integer vid,
                          @Param("isLike")Boolean isLike);

    int decreaseLikeTimes(@Param("vid")Integer vid,
                          @Param("isLike")Boolean isLike);

    int increaseCoinTimes(@Param("vid")Integer vid,
                          @Param("coin")Byte coin);

    int increaseCollectTimes(Integer vid);

    int decreaseCollectTimes(Integer vid);

    int increaseShareTimes(Integer vid);

    int countLikeByUid(Integer uid);

}
