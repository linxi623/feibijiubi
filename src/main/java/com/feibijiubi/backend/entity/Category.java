package com.feibijiubi.backend.entity;

import lombok.Data;


@Data
public class Category {
    private String mcId;
    private String scId;
    private String mcName;
    private String scName;
    private String description;
    private String rcmTags;
}
