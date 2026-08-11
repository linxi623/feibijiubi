package com.feibijiubi.backend.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class CursorDTO {
    private String cursor;
    @Min(value = 1, message = "每页数量不能小于1")
    @Max(value = 25, message = "每页数量不能多余25")
    private Integer size = 15;
    private String mcId;
    private String scId;
}
