package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.Video;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface VideoMapper {
    int insert(Video record);

    Video selectByVid(Integer vid);

    List<Video> selectPublishedList();
}
