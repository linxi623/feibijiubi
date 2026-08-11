package com.feibijiubi.backend.vo;


import lombok.Data;

import java.util.List;

@Data
public class CategoryChildrenVO {
    private String scId;
    private String scName;
    private String description;
    private List<String> rcmTags;
}
