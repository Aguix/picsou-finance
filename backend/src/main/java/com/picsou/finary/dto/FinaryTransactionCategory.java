package com.picsou.finary.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FinaryTransactionCategory(
    Integer id,
    String name
) {}
