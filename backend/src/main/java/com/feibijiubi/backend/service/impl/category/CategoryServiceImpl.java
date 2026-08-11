package com.feibijiubi.backend.service.impl.category;

import com.fasterxml.jackson.core.type.TypeReference;
import com.feibijiubi.backend.entity.Category;
import com.feibijiubi.backend.mapper.CategoryMapper;
import com.feibijiubi.backend.service.category.CategoryService;
import com.feibijiubi.backend.utils.redis.RedisConstants;
import com.feibijiubi.backend.utils.redis.RedisKeyUtils;
import com.feibijiubi.backend.utils.redis.RedisUtils;
import com.feibijiubi.backend.vo.CategoryChildrenVO;
import com.feibijiubi.backend.vo.CategoryParentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;
    private final RedisUtils redisUtils;

    @Override
    public List<CategoryParentVO> getCategories() {
        String key = RedisKeyUtils.categoryTree();

        List<CategoryParentVO> parentList = redisUtils.getJson(
                key,
                new TypeReference<List<CategoryParentVO>>() {}
        );

        if (parentList != null) {
            return parentList;
        }

        List<Category> categories = categoryMapper.categoryList();
        List<CategoryParentVO> categoryTree = buildCategoryTree(categories);

        redisUtils.setJson(
                key,
                categoryTree,
                Duration.ofSeconds(RedisConstants.CATEGORY_EXPIRE_TIME)
        );

        return categoryTree;
    }

    private List<CategoryParentVO> buildCategoryTree(
            List<Category> categories
    ) {
        if (categories == null || categories.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, CategoryParentVO> parentMap = new LinkedHashMap<>();

        for (Category category : categories) {
            CategoryParentVO parent = parentMap.computeIfAbsent(
                    category.getMcId(),
                    key -> createParentCategory(category)
            );

            parent.getChildren().add(createChildCategory(category));
        }

        return new ArrayList<>(parentMap.values());
    }

    private CategoryParentVO createParentCategory(Category category) {
        CategoryParentVO parent = new CategoryParentVO();
        parent.setMcId(category.getMcId());
        parent.setMcName(category.getMcName());
        parent.setChildren(new ArrayList<>());
        return parent;
    }

    private CategoryChildrenVO createChildCategory(Category category) {
        CategoryChildrenVO child = new CategoryChildrenVO();
        child.setScId(category.getScId());
        child.setScName(category.getScName());
        child.setDescription(category.getDescription());
        child.setRcmTags(parseTags(category.getRcmTags()));
        return child;
    }

    private List<String> parseTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return Collections.emptyList();
        }

        return Arrays.stream(tags.split("\\R"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}

