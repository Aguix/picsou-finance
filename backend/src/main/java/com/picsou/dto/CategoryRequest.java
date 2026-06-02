package com.picsou.dto;

import com.picsou.model.CategoryKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CategoryRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull CategoryKind kind,
    String color,
    String icon,
    Integer sortOrder
) {}
