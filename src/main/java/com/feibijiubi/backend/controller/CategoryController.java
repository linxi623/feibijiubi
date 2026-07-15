package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.entity.Category;
import com.feibijiubi.backend.service.category.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/category")
public class CategoryController {
    private final CategoryService categoryService;
    public CategoryController(final CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping()
    public ApiResponse<List<Category>> getAll() {
        List<Category> list= categoryService.getCategories();
        return ApiResponse.success(list);
    }
}
