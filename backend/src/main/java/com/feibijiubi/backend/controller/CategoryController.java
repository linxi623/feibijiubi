package com.feibijiubi.backend.controller;

import com.feibijiubi.backend.common.ApiResponse;
import com.feibijiubi.backend.entity.Category;
import com.feibijiubi.backend.service.category.CategoryService;
import com.feibijiubi.backend.vo.CategoryParentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryController {
    private final CategoryService categoryService;

    @GetMapping()
    public ApiResponse<List<CategoryParentVO>> getAll() {
        List<CategoryParentVO> list= categoryService.getCategories();
        return ApiResponse.success(list);
    }

}
