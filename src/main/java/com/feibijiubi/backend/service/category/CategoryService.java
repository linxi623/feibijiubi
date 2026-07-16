package com.feibijiubi.backend.service.category;

import com.feibijiubi.backend.entity.Category;
import com.feibijiubi.backend.vo.CategoryParentVO;

import java.util.List;

public interface CategoryService {
    List<CategoryParentVO> getCategories();
}
