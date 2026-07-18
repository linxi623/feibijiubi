package com.feibijiubi.backend.mapper;

import org.apache.ibatis.annotations.Mapper;


@Mapper
public interface VideoStatusSequenceMapper {
    int ensureExists(Integer vid);

    int increase(Integer vid);

    Long selectCurrent(Integer vid);
}
