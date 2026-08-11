package com.feibijiubi.backend.vo;


import lombok.Data;

import java.util.List;

@Data
public class CursorPageVO<T> {
    private List<T> items;
    private String nextCursor;
    private Boolean hasMore;
}
