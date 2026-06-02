package com.picsou.dto;

import jakarta.validation.constraints.NotNull;

/** Assign a managed category to a transaction, optionally learning a rule from it. */
public record CategorizeRequest(
    @NotNull Long categoryId,
    boolean createRule
) {}
