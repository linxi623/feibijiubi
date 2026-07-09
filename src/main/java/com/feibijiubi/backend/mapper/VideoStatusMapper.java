package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.VideoStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;


@Mapper
public interface VideoStatusMapper {
    int insertDefault(Integer vid);

    VideoStatus selectByVid(Integer vid);

    int increasePlayTimes(Integer vid);

    int increaseLikeTimes(Integer vid);

    int decreaseLikeTimes(Integer vid);

    int increaseCoinTimes(@Param("vid")Integer vid,
                          @Param("coin")Integer coin);

    int increaseCollectTimes(Integer vid);

    int decreaseCollectTimes(Integer vid);
}
