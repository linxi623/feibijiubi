package com.feibijiubi.backend.vo;

import lombok.Data;

import java.util.List;

@Data
public class CategoryParentVO {
    private String mcId;
    private String mcName;
    private List<CategoryChildrenVO> children;
}
