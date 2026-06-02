package com.picsou.dto;

import com.picsou.model.Category;
import com.picsou.model.CategoryKind;

public record CategoryResponse(
    Long id,
    String name,
    CategoryKind kind,
    String color,
    String icon,
    boolean isDefault,
    boolean archived,
    int sortOrder
) {
    public static CategoryResponse from(Category c) {
        return new CategoryResponse(
            c.getId(), c.getName(), c.getKind(), c.getColor(), c.getIcon(),
            c.isDefault(), c.isArchived(), c.getSortOrder()
        );
    }
}
