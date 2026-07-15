package com.feibijiubi.backend.service.impl.category;

import com.feibijiubi.backend.entity.Category;
import com.feibijiubi.backend.mapper.CategoryMapper;
import com.feibijiubi.backend.service.category.CategoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryServiceImpl implements CategoryService {
    private final CategoryMapper categoryMapper;
    public CategoryServiceImpl(final CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    @Override
    public List<Category> getCategories() {
        return categoryMapper.categoryList();
    }
}
