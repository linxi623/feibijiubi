package com.feibijiubi.backend.video.mapper;

import com.feibijiubi.backend.video.entity.Category;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CategoryMapper {
    List<Category> categoryList();
}
