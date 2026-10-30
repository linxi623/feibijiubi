package com.feibijiubi.backend.video.service.category;

import com.feibijiubi.backend.video.entity.Category;
import com.feibijiubi.backend.video.vo.CategoryParentVO;

import java.util.List;

public interface CategoryService {
    List<CategoryParentVO> getCategories();
}
